#!/usr/bin/env bash
# Escenario 11 — Latencia (spec global §4.2 y §6): mide cuánto tarda una venta en reflejarse en el Catálogo.
# Registra LATENCY_SALES ventas (20 por defecto) de GORRA-SPORT-NEGRA alternando POS y online, una tras otra; lee el
# lastEventLatencyMs de cada una, calcula mínimo, promedio, p95 y máximo, y escribe el informe (por defecto
# docs/results/latency.md, o LATENCY_REPORT). Falla si el p95 supera LATENCY_P95_LIMIT_MS (1000 ms por defecto).
# Consume LATENCY_SALES unidades de stock (sembrado 50: caben 2 ejecuciones de 20 por reinicio).
# Uso: e2e/11-latency.sh [--demo]
set -uo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
source "$(dirname "${BASH_SOURCE[0]}")/report.sh"
e2e_init "$@"
SKU="GORRA-SPORT-NEGRA"
SALES="${LATENCY_SALES:-20}"
P95_LIMIT_MS="${LATENCY_P95_LIMIT_MS:-1000}"
REPORT_FILE="${LATENCY_REPORT:-$E2E_ROOT/docs/results/latency.md}"

[[ "$SALES" =~ ^[1-9][0-9]*$ ]] || fail_precondition "LATENCY_SALES debe ser un entero positivo (valor: $SALES)"
[[ "$P95_LIMIT_MS" =~ ^[1-9][0-9]*$ ]] || fail_precondition "LATENCY_P95_LIMIT_MS debe ser un entero positivo (valor: $P95_LIMIT_MS)"

require_stack
require_synced "$SKU"
require_stock "$SKU" "$SALES"
stock_before="$(catalog_stock "$SKU")"

step "Registra $SALES ventas de $SKU, una tras otra, alternando POS y online"
latencies=()
samples=()
for index in $(seq 1 "$SALES"); do
  channel="POS"
  (( index % 2 == 0 )) && channel="ONLINE"
  sell "$SKU" "$channel"
  if [[ "$SELL_STATUS" != "201" ]]; then
    check "la venta $index ($channel) responde 201" "$SELL_STATUS" "201"
    break
  fi
  wait_until "venta $index ($channel): el Catálogo muestra stock $((stock_before - index))" \
    catalog_stock_is "$SKU" "$((stock_before - index))" || break
  latency="$(catalog_field "$SKU" '.lastEventLatencyMs')"
  if [[ "$(is_integer "$latency")" != "yes" ]]; then
    check "lastEventLatencyMs de la venta $index se informa" "$latency" "un entero"
    break
  fi
  echo "INFO: venta $index ($channel): latencia ${latency} ms"
  latencies+=("$latency")
  samples+=("$channel:$latency")
done
check "se midieron las $SALES ventas" "${#latencies[@]}" "$SALES"

if (( ${#latencies[@]} == SALES )); then
  statistics "${latencies[@]}"
  step "Resultado: mínimo ${STAT_MIN} ms, promedio ${STAT_AVERAGE} ms, p95 ${STAT_P95} ms, máximo ${STAT_MAX} ms"
  check "el p95 (${STAT_P95} ms) no supera ${P95_LIMIT_MS} ms" \
    "$([[ "$STAT_P95" -le "$P95_LIMIT_MS" ]] && echo yes || echo no)" "yes"
  metric latency_min_ms "$STAT_MIN"
  metric latency_average_ms "$STAT_AVERAGE"
  metric latency_p95_ms "$STAT_P95"
  metric latency_max_ms "$STAT_MAX"
  mkdir -p "$(dirname "$REPORT_FILE")"
  render_latency_report "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$SKU" "$P95_LIMIT_MS" \
    "$STAT_MIN" "$STAT_AVERAGE" "$STAT_P95" "$STAT_MAX" "${samples[@]}" >"$REPORT_FILE"
  echo "INFO: informe de latencia escrito en $REPORT_FILE"
fi

finish "11 Latencia"
