#!/usr/bin/env bash
# Informes y ejecución de escenarios (spec E2E §3 y §4). Se carga con `source` después de lib.sh; no ejecuta nada
# por sí sola. Las usan 11-latency.sh y run-all.sh, y las prueba selftest.sh sin Docker.

# "Antes" del proceso por lotes del retail (spec global §6): cada 30 min, así que el peor caso es 30 min y el
# promedio ~15 min.
BEFORE_WORST_CASE_MS=1800000
BEFORE_AVERAGE_MS=900000

# Resultados de run_scenario, en el orden de ejecución.
RESULT_NAMES=()
RESULT_STATUS=()
RESULT_DURATION_MS=()
METRIC_LINES=()

# format_duration_ms <ms>: duración en segundos con un decimal (por ejemplo "6.5 s").
format_duration_ms() { awk -v ms="$1" 'BEGIN { printf "%.1f s", ms / 1000 }'; }

# improvement_factor <antes_ms> <después_ms>: cuántas veces menor es el segundo valor (entero hacia abajo; un
# "después" menor que 1 ms se toma como 1 ms).
improvement_factor() {
  awk -v before="$1" -v after="$2" 'BEGIN { if (after < 1) after = 1; printf "%d", before / after }'
}

# render_latency_report <fecha UTC> <sku> <límite p95 ms> <mínimo> <promedio> <p95> <máximo> <CANAL:ms...>
# Imprime el informe de latencia en Markdown (docs/results/latency.md).
render_latency_report() {
  local timestamp="$1" sku="$2" limit="$3" minimum="$4" average="$5" p95="$6" maximum="$7"
  shift 7
  local verdict="sí" sample index=0
  awk -v p95="$p95" -v limit="$limit" 'BEGIN { exit !(p95 > limit) }' && verdict="no"
  printf '# Latencia de propagación venta → Catálogo\n\n'
  printf -- '- **Fecha (UTC):** %s\n' "$timestamp"
  printf -- '- **Muestras:** %s ventas de `%s`, alternando POS y online, una tras otra\n' "$#" "$sku"
  printf -- '- **Definición:** `lastEventLatencyMs` del Catálogo = `updatedAt − occurredAt` (momento en que el\n'
  printf '  Catálogo aplica el evento menos el momento de la venta); los contenedores comparten el reloj del anfitrión\n'
  printf -- '- **Objetivo:** p95 por debajo de %s ms; **cumple: %s**\n\n' "$limit" "$verdict"
  printf '| Mínimo | Promedio | p95 | Máximo |\n|---|---|---|---|\n'
  printf '| %s ms | %s ms | %s ms | %s ms |\n\n' "$minimum" "$average" "$p95" "$maximum"
  printf '## Comparación con el proceso por lotes ("antes")\n\n'
  printf '| | Antes (lotes cada 30 min) | Después (eventos) |\n|---|---|---|\n'
  printf '| Peor caso | 30 min (%s ms) | p95 medido: %s ms |\n' "$BEFORE_WORST_CASE_MS" "$p95"
  printf '| Promedio | ~15 min (%s ms) | promedio medido: %s ms |\n' "$BEFORE_AVERAGE_MS" "$average"
  printf '| Ventana de sobreventa | Hasta 30 min | Hasta la latencia medida; Inventario rechaza toda venta sin stock |\n\n'
  printf 'El peor caso medido (p95) es unas **%s veces** menor que el peor caso del proceso por lotes.\n\n' \
    "$(improvement_factor "$BEFORE_WORST_CASE_MS" "$p95")"
  printf '## Muestras\n\n| # | Canal | Latencia (ms) |\n|---|---|---|\n'
  for sample in "$@"; do
    index=$((index + 1))
    printf '| %s | %s | %s |\n' "$index" "${sample%%:*}" "${sample#*:}"
  done
}

# run_scenario <script>: ejecuta un escenario como subproceso (nunca con source: sus traps no deben mezclarse), muestra
# su salida en vivo y registra nombre, resultado, duración y, solo si terminó bien, sus líneas METRIC.
run_scenario() {
  local script="$1" name log start status line
  name="$(basename "$script" .sh)"
  log="$(mktemp)"
  printf '\n=== %s ===\n' "$name"
  start="$(now_ms)"
  bash "$script" 2>&1 | tee "$log"
  status="${PIPESTATUS[0]}"
  RESULT_NAMES+=("$name")
  RESULT_DURATION_MS+=("$(( $(now_ms) - start ))")
  if [[ "$status" == "0" ]]; then
    RESULT_STATUS+=("PASS")
    while IFS= read -r line; do
      METRIC_LINES+=("${line#METRIC }")
    done < <(grep '^METRIC ' "$log")
  else
    RESULT_STATUS+=("FAIL")
  fi
  rm -f "$log"
}

# count_passed: cuántos escenarios registrados terminaron bien.
count_passed() {
  local count=0 status
  for status in "${RESULT_STATUS[@]}"; do
    [[ "$status" == "PASS" ]] && count=$((count + 1))
  done
  echo "$count"
}

# render_summary: resumen en texto de los escenarios ejecutados y sus métricas.
render_summary() {
  local index
  printf '\n== Resumen ==\n'
  printf '%-28s %-9s %s\n' "Escenario" "Resultado" "Duración"
  for index in "${!RESULT_NAMES[@]}"; do
    printf '%-28s %-9s %s\n' "${RESULT_NAMES[index]}" "${RESULT_STATUS[index]}" \
      "$(format_duration_ms "${RESULT_DURATION_MS[index]}")"
  done
  if (( ${#METRIC_LINES[@]} > 0 )); then
    printf '\nMétricas:\n'
    printf '  %s\n' "${METRIC_LINES[@]}"
  fi
  printf '\n%s de %s escenarios OK\n' "$(count_passed)" "${#RESULT_NAMES[@]}"
}

# render_run_report <fecha UTC>: lo mismo que render_summary, en Markdown (opción --report de run-all.sh).
render_run_report() {
  local index metric
  printf '# Resultados de la ejecución E2E\n\n'
  printf -- '- **Fecha (UTC):** %s\n' "$1"
  printf -- '- **Resultado:** %s de %s escenarios OK\n\n' "$(count_passed)" "${#RESULT_NAMES[@]}"
  printf '| Escenario | Resultado | Duración |\n|---|---|---|\n'
  for index in "${!RESULT_NAMES[@]}"; do
    printf '| %s | %s | %s |\n' "${RESULT_NAMES[index]}" "${RESULT_STATUS[index]}" \
      "$(format_duration_ms "${RESULT_DURATION_MS[index]}")"
  done
  if (( ${#METRIC_LINES[@]} > 0 )); then
    printf '\n## Métricas\n\n| Métrica | Valor |\n|---|---|\n'
    for metric in "${METRIC_LINES[@]}"; do
      printf '| %s | %s |\n' "${metric%%=*}" "${metric#*=}"
    done
  fi
}
