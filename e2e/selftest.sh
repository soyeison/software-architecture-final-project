#!/usr/bin/env bash
# Prueba de la lógica de lib.sh que tiene riesgo real (spec E2E §4). No requiere Docker.
# Uso: e2e/selftest.sh
set -uo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

failures=0
expect() { # expect <descripción> <obtenido> <esperado>
  if [[ "$2" == "$3" ]]; then echo "ok:   $1"; else echo "FALLA: $1 (obtenido: $2, esperado: $3)"; failures=$((failures + 1)); fi
}

# --- statistics ---
statistics 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20
expect "statistics: mínimo de 1..20" "$STAT_MIN" "1"
expect "statistics: promedio de 1..20" "$STAT_AVERAGE" "10.5"
expect "statistics: p95 de 1..20 (rango 19)" "$STAT_P95" "19"
expect "statistics: máximo de 1..20" "$STAT_MAX" "20"

statistics 900 120 480 5
expect "statistics: ordena valores desordenados (mínimo)" "$STAT_MIN" "5"
expect "statistics: ordena valores desordenados (máximo)" "$STAT_MAX" "900"
expect "statistics: p95 de 4 valores es el mayor" "$STAT_P95" "900"
expect "statistics: promedio de 4 valores" "$STAT_AVERAGE" "376.2"

statistics 42
expect "statistics: un solo valor (p95)" "$STAT_P95" "42"

statistics >/dev/null 2>&1
expect "statistics: sin valores devuelve 1" "$?" "1"

# --- is_integer ---
expect "is_integer acepta 386" "$(is_integer 386)" "yes"
expect "is_integer rechaza null" "$(is_integer null)" "no"
expect "is_integer rechaza vacío" "$(is_integer '')" "no"

# --- check ---
E2E_STATUS=0
check "descripción" "a" "a" >/dev/null
expect "check: igual no marca fallo" "$E2E_STATUS" "0"
check "descripción" "a" "b" >/dev/null
expect "check: distinto marca fallo" "$E2E_STATUS" "1"

# --- wait_until ---
E2E_STATUS=0
WAIT_TIMEOUT_SECONDS=2
wait_until "condición inmediata" true >/dev/null
expect "wait_until: éxito inmediato devuelve 0" "$?" "0"
expect "wait_until: éxito inmediato no marca fallo" "$E2E_STATUS" "0"

marker="$(mktemp -u)"
( sleep 0.6; touch "$marker" ) &
wait_until "condición que se cumple a los 0,6 s" test -e "$marker" >/dev/null
expect "wait_until: éxito tras esperar devuelve 0" "$?" "0"
expect "wait_until: registra al menos 500 ms esperados" "$([[ $WAITED_MS -ge 500 ]] && echo yes || echo no)" "yes"
rm -f "$marker"

wait_until "condición imposible" false >/dev/null
expect "wait_until: tiempo agotado devuelve 1" "$?" "1"
expect "wait_until: tiempo agotado marca fallo" "$E2E_STATUS" "1"
expect "wait_until: respeta el tiempo máximo (≥ 2000 ms)" "$([[ $WAITED_MS -ge 2000 ]] && echo yes || echo no)" "yes"

# --- synced (con catalog_stock e inventory_stock simulados, sin Docker) ---
synced_with() { # synced_with <catálogo> <inventario>
  eval "catalog_stock() { echo '$1'; }; inventory_stock() { echo '$2'; }"
  synced X && echo yes || echo no
}
expect "synced: dos enteros iguales" "$(synced_with 5 5)" "yes"
expect "synced: enteros distintos" "$(synced_with 5 4)" "no"
expect "synced: ambos vacíos" "$(synced_with '' '')" "no"
expect "synced: ambos null" "$(synced_with null null)" "no"
expect "synced: catálogo vacío e Inventario entero" "$(synced_with '' 5)" "no"

# --- Inyección de eventos (sin Docker) ---
identifier="$(new_uuid)"
expect "new_uuid: formato UUID" \
  "$([[ "$identifier" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] && echo yes || echo no)" "yes"
expect "new_uuid: dos valores distintos" "$([[ "$(new_uuid)" != "$(new_uuid)" ]] && echo yes || echo no)" "yes"

first_base="$(version_base)"
expect "version_base: milisegundos desde la época (13 dígitos)" \
  "$([[ "$first_base" =~ ^[0-9]{13}$ ]] && echo yes || echo no)" "yes"
sleep 0.01
expect "version_base: crece con el tiempo" "$([[ "$(version_base)" -gt "$first_base" ]] && echo yes || echo no)" "yes"

event="$(build_event "$identifier" "CALCETINES-PACK-3" 15 "$first_base" ONLINE)"
expect "build_event: JSON válido con los 7 campos del contrato" \
  "$(jq -r '[.eventId, .eventType, .sku, .newStock, .stockVersion, .channel, .occurredAt] | map(select(. != null)) | length' <<<"$event")" "7"
expect "build_event: eventType" "$(jq -r '.eventType' <<<"$event")" "InventoryStockChanged"
expect "build_event: eventId" "$(jq -r '.eventId' <<<"$event")" "$identifier"
expect "build_event: newStock es un número" "$(jq -r '.newStock | type' <<<"$event")" "number"
expect "build_event: stockVersion es un número con el valor dado" "$(jq -r '.stockVersion' <<<"$event")" "$first_base"
expect "build_event: canal indicado" "$(jq -r '.channel' <<<"$event")" "ONLINE"
expect "build_event: canal por defecto POS" "$(build_event "$identifier" X 1 1 | jq -r '.channel')" "POS"
expect "build_event: occurredAt en UTC con milisegundos" \
  "$([[ "$(jq -r '.occurredAt' <<<"$event")" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$ ]] && echo yes || echo no)" "yes"

awkward_body=$'{"a": "con \\"comillas\\"",\n "b": 1}'
publish_body="$(management_publish_body "$awkward_body")"
expect "management_publish_body: conserva el cuerpo tal cual (comillas y saltos de línea)" \
  "$(jq -r '.payload' <<<"$publish_body")" "$awkward_body"
expect "management_publish_body: clave de enrutamiento" "$(jq -r '.routing_key' <<<"$publish_body")" "inventory.stock.changed"
expect "management_publish_body: codificación del cuerpo" "$(jq -r '.payload_encoding' <<<"$publish_body")" "string"
expect "management_publish_body: mensaje persistente" "$(jq -r '.properties.delivery_mode' <<<"$publish_body")" "2"

# --- catalog_log_count con `compose` simulado ---
failing_logs_output="$(compose() { return 1; }; catalog_log_count X)"
expect "catalog_log_count: si compose logs falla no imprime conteo" "$failing_logs_output" ""
expect "catalog_log_count: si compose logs falla devuelve 1" \
  "$(compose() { return 1; }; catalog_log_count X >/dev/null && echo yes || echo no)" "no"
expect "catalog_log_count_is X 0: falso si compose logs falla" \
  "$(compose() { return 1; }; catalog_log_count_is X 0 && echo yes || echo no)" "no"
expect "catalog_log_count_is X 0: verdadero si el log no contiene el patrón" \
  "$(compose() { printf 'linea uno\nlinea dos\n'; }; catalog_log_count_is X 0 && echo yes || echo no)" "yes"
expect "catalog_log_count: cuenta las líneas con el patrón" \
  "$(compose() { printf 'a X b\nnada\nX\n'; }; catalog_log_count X)" "2"
expect "catalog_log_count: el patrón es texto literal (a.b no coincide con axb)" \
  "$(compose() { printf 'axb\na.b\n'; }; catalog_log_count 'a.b')" "1"

# --- publish_event con `publish_raw` simulado ---
# La función simulada imprime "llamada" (y el cuerpo) para detectar si se la invocó.
invalid_stock_result="$(publish_raw() { echo "llamada: $1"; }; publish_event X abc 1 && echo "codigo=0" || echo "codigo=1")"
expect "publish_event: stock no numérico devuelve 1 sin publicar" "$invalid_stock_result" "codigo=1"
invalid_version_result="$(publish_raw() { echo "llamada: $1"; }; publish_event X 1 abc && echo "codigo=0" || echo "codigo=1")"
expect "publish_event: versión no numérica devuelve 1 sin publicar" "$invalid_version_result" "codigo=1"
valid_result="$(publish_raw() { echo "llamada"; echo "$1" | jq -r '.sku'; return 0; }; publish_event X 1 2 "$identifier"; echo "codigo=$?")"
expect "publish_event: con valores válidos publica un cuerpo JSON y devuelve 0" \
  "$valid_result" $'llamada\nX\ncodigo=0'
failing_publish_code="$(publish_raw() { return 1; }; publish_event X 1 2 >/dev/null; echo "$?")"
expect "publish_event: propaga el código de salida de publish_raw" "$failing_publish_code" "1"

# --- Caídas de servicios y métricas (sin Docker: compose, curl y wait_until simulados) ---
first_now="$(now_ms)"
expect "now_ms: milisegundos desde la época (13 dígitos)" \
  "$([[ "$first_now" =~ ^[0-9]{13}$ ]] && echo yes || echo no)" "yes"
expect "metric: formato fijo para run-all.sh" "$(metric catalog_catch_up_ms 1234)" "METRIC catalog_catch_up_ms=1234"

calls_file="$(mktemp)"
registry_state() { # registry_state <comando...>: ejecuta con un compose simulado y muestra el registro y las llamadas
  ( compose() { echo "$*" >>"$calls_file"; return "${COMPOSE_STATUS:-0}"; }
    STOPPED_SERVICES=()
    "$@"
    echo "registro=[${STOPPED_SERVICES[*]}]" )
}
two_stops_one_start() { stop_service catalog-service; stop_service rabbitmq; start_service catalog-service; }
: >"$calls_file"
expect "stop_service y start_service: solo queda registrado el que sigue detenido" \
  "$(registry_state two_stops_one_start)" "registro=[rabbitmq]"
expect "stop_service: llama a docker compose stop" \
  "$(grep -c '^stop catalog-service$' "$calls_file")" "1"
expect "start_service: espera a que esté sano y no recrea el contenedor" \
  "$(grep -c '^up -d --wait --wait-timeout 120 --no-recreate catalog-service$' "$calls_file")" "1"

restore_all() { stop_service catalog-service; stop_service rabbitmq; restore_services 2>/dev/null; }
: >"$calls_file"
expect "restore_services: levanta todo lo que quedó detenido y vacía el registro" \
  "$(registry_state restore_all)" "registro=[]"
expect "restore_services: levantó catalog-service" \
  "$(grep -c '^up .* catalog-service$' "$calls_file")" "1"
expect "restore_services: levantó rabbitmq" "$(grep -c '^up .* rabbitmq$' "$calls_file")" "1"

failing_stop() { COMPOSE_STATUS=1 stop_service rabbitmq; echo "estado=$?"; }
expect "stop_service: si compose falla devuelve 1 y deja registrado el servicio" \
  "$(registry_state failing_stop | paste -sd' ')" "estado=1 registro=[rabbitmq]"
twice_stop() { stop_service rabbitmq; stop_service rabbitmq; }
expect "stop_service: detener dos veces el mismo servicio no lo registra duplicado" \
  "$(registry_state twice_stop)" "registro=[rabbitmq]"
failing_start() { stop_service rabbitmq; COMPOSE_STATUS=1 start_service rabbitmq; echo "estado=$?"; }
expect "start_service: si compose falla devuelve 1 y conserva el registro" \
  "$(registry_state failing_start | paste -sd' ')" "estado=1 registro=[rabbitmq]"
rm -f "$calls_file"

# restore_services debe avisar por el stderr original aunque se ejecute dentro de `>/dev/null 2>&1`
# (como pasa cuando la señal llega durante un predicado de wait_until).
restore_messages_file="$(mktemp)"
restore_under_redirect() { # restore_under_redirect <estado de compose>
  ( compose() { return "$1"; }
    STOPPED_SERVICES=(rabbitmq)
    install_restore_trap 2>"$restore_messages_file"
    trap - EXIT INT TERM
    restore_services >/dev/null 2>&1
    echo "registro=[${STOPPED_SERVICES[*]}]" ) 2>>"$restore_messages_file"
}
: >"$restore_messages_file"
restore_under_redirect 0 >/dev/null
expect "restore_services: el aviso 'Restaurando' llega al stderr original aunque esté redirigido" \
  "$(grep -c '^Restaurando rabbitmq' "$restore_messages_file")" "1"
: >"$restore_messages_file"
expect "restore_services: si compose falla conserva el registro" "$(restore_under_redirect 1)" "registro=[rabbitmq]"
expect "restore_services: el aviso FAIL llega al stderr original" \
  "$(grep -c '^FAIL: no se pudo restaurar rabbitmq' "$restore_messages_file")" "1"
rm -f "$restore_messages_file"

queue_consumers_with() { # queue_consumers_with <consumidores> <comando...>: simula rabbitmqctl list_queues name consumers
  ( simulated_consumers="$1"
    compose() { printf 'catalog.inventory-stock-changed\t%s\r\nother.queue\t5\n' "$simulated_consumers"; }
    "${@:2}" )
}
expect "queue_consumers: lee la columna de consumidores de la cola" "$(queue_consumers_with 1 queue_consumers catalog.inventory-stock-changed)" "1"
expect "queue_consumers_is: cero consumidores" \
  "$(queue_consumers_with 0 queue_consumers_is catalog.inventory-stock-changed 0 && echo yes || echo no)" "yes"
expect "queue_consumers_is: distinto" \
  "$(queue_consumers_with 1 queue_consumers_is catalog.inventory-stock-changed 0 && echo yes || echo no)" "no"

outbox_sample='[{"eventId":"a","status":"PENDING"},{"eventId":"b","status":"PENDING"},{"eventId":"z","status":"PENDING"}]'
outbox_count_for() { ( curl() { echo "$outbox_sample"; }; outbox_count_with_status "$@" ); }
expect "outbox_count_with_status: cuenta solo los eventId indicados" "$(outbox_count_for PENDING a b c)" "2"
expect "outbox_count_with_status: ninguno coincide" "$(outbox_count_for PENDING x y)" "0"
outbox_is_for() { ( curl() { echo "$outbox_sample"; }; outbox_count_with_status_is "$@" && echo yes || echo no ); }
expect "outbox_count_with_status_is: coincide" "$(outbox_is_for PENDING 2 a b c)" "yes"
expect "outbox_count_with_status_is: no coincide" "$(outbox_is_for PENDING 3 a b c)" "no"
expect "outbox_count_with_status: respuesta que no es JSON no cuenta nada" \
  "$( ( curl() { echo 'caído'; }; outbox_count_with_status PENDING a 2>/dev/null ) )" ""
expect "outbox_count_with_status_is: respuesta que no es JSON es falso" \
  "$( ( curl() { echo 'caído'; }; outbox_count_with_status_is PENDING 0 a 2>/dev/null && echo yes || echo no ) )" "no"

log_sample='2026-10-12 INFO [sku=MOCHILA] [eventId=aaa] c.r.c.s.ApplyStockChangeService : event_applied sku=MOCHILA eventId=aaa previous=10 new=9 version=2 latencyMs=5
2026-10-12 INFO [sku=OTRO] [eventId=zzz] c.r.c.s.ApplyStockChangeService : event_applied sku=OTRO eventId=zzz previous=3 new=2 version=2 latencyMs=5
2026-10-12 INFO [sku=MOCHILA] [eventId=ccc] c.r.c.s.ApplyStockChangeService : event_applied sku=MOCHILA eventId=ccc previous=9 new=8 version=3 latencyMs=5
2026-10-12 INFO [sku=MOCHILA] [eventId=bbb] c.r.c.s.ApplyStockChangeService : event_duplicate sku=MOCHILA eventId=bbb
2026-10-12 INFO [sku=MOCHILA] [eventId=bbb] c.r.c.s.ApplyStockChangeService : event_applied sku=MOCHILA eventId=bbb previous=8 new=7 version=4 latencyMs=5'
applied_for() { ( compose() { echo "$log_sample"; }; catalog_applied_sequence "$@" | paste -sd' ' ); }
expect "catalog_applied_sequence: devuelve los eventId pedidos en el orden del log, una vez cada uno" \
  "$(applied_for MOCHILA bbb ccc aaa)" "aaa ccc bbb"
expect "catalog_applied_sequence: ignora otros SKU y eventId no pedidos" "$(applied_for MOCHILA aaa)" "aaa"
expect "catalog_applied_sequence: un evento que no se aplicó no aparece" "$(applied_for MOCHILA nunca)" ""
unreadable_applied() { ( compose() { return 1; }; catalog_applied_sequence MOCHILA aaa; echo "estado=$?" ); }
expect "catalog_applied_sequence: devuelve 1 sin imprimir si no puede leer el log" "$(unreadable_applied)" "estado=1"

# El tiempo máximo de espera se puede cambiar por llamada y vuelve a su valor después.
WAIT_TIMEOUT_SECONDS=2
WAIT_TIMEOUT_SECONDS=1 wait_until "condición imposible con espera corta" false >/dev/null
expect "wait_until: el tiempo máximo por llamada se respeta (1 s)" \
  "$([[ $WAITED_MS -ge 1000 && $WAITED_MS -lt 1800 ]] && echo yes || echo no)" "yes"
expect "wait_until: el tiempo máximo por llamada no altera el global" "$WAIT_TIMEOUT_SECONDS" "2"
E2E_STATUS=0

# --- Informes, ejecución de escenarios y run-all (sin Docker: escenarios simulados) ---
source "$(dirname "${BASH_SOURCE[0]}")/report.sh"

expect "format_duration_ms: segundos con un decimal" "$(format_duration_ms 6511)" "6.5 s"
expect "format_duration_ms: cero" "$(format_duration_ms 0)" "0.0 s"
expect "format_duration_ms: más de un minuto" "$(format_duration_ms 125400)" "125.4 s"
expect "improvement_factor: 30 min frente a 571 ms" "$(improvement_factor 1800000 571)" "3152"
expect "improvement_factor: admite decimales" "$(improvement_factor 900000 366.1)" "2458"
expect "improvement_factor: un después de 0 ms se toma como 1 ms" "$(improvement_factor 1800000 0)" "1800000"

latency_report="$(render_latency_report 2026-10-12T10:00:00Z GORRA-SPORT-NEGRA 1000 51 312.4 571 580 POS:51 ONLINE:571 POS:315)"
expect "render_latency_report: cabecera" "$(head -1 <<<"$latency_report")" "# Latencia de propagación venta → Catálogo"
expect "render_latency_report: fecha" "$(grep -c 'Fecha (UTC):\*\* 2026-10-12T10:00:00Z' <<<"$latency_report")" "1"
expect "render_latency_report: número de muestras" "$(grep -c 'Muestras:\*\* 3 ventas de `GORRA-SPORT-NEGRA`' <<<"$latency_report")" "1"
expect "render_latency_report: fila de estadísticas" "$(grep -c '^| 51 ms | 312.4 ms | 571 ms | 580 ms |$' <<<"$latency_report")" "1"
expect "render_latency_report: comparación con el antes" "$(grep -c 'p95 medido: 571 ms' <<<"$latency_report")" "1"
expect "render_latency_report: factor de mejora" "$(grep -c 'unas \*\*3152 veces\*\*' <<<"$latency_report")" "1"
expect "render_latency_report: cumple el objetivo" "$(grep -c 'cumple: sí' <<<"$latency_report")" "1"
expect "render_latency_report: una fila por muestra" "$(grep -c '^| [0-9]* | \(POS\|ONLINE\) | [0-9]* |$' <<<"$latency_report")" "3"
expect "render_latency_report: muestra con su canal" "$(grep -c '^| 2 | ONLINE | 571 |$' <<<"$latency_report")" "1"
over_limit_report="$(render_latency_report 2026-10-12T10:00:00Z X 500 51 312.4 571 580 POS:571)"
expect "render_latency_report: no cumple si el p95 supera el límite" "$(grep -c 'cumple: no' <<<"$over_limit_report")" "1"

fake_dir="$(mktemp -d)"
printf '#!/usr/bin/env bash\necho "salida del escenario ok"\necho "METRIC alpha_ms=10"\nexit 0\n' >"$fake_dir/01-ok.sh"
printf '#!/usr/bin/env bash\necho "salida del escenario roto"\necho "METRIC beta_ms=20"\nexit 1\n' >"$fake_dir/02-broken.sh"
printf '#!/usr/bin/env bash\necho "METRIC gamma_ms=30"\nexit 0\n' >"$fake_dir/03-ok-too.sh"
scenario_output="$(mktemp)"
run_scenario "$fake_dir/01-ok.sh" >"$scenario_output"
run_scenario "$fake_dir/02-broken.sh" >>"$scenario_output"
run_scenario "$fake_dir/03-ok-too.sh" >>"$scenario_output"
expect "run_scenario: registra los nombres en orden" "${RESULT_NAMES[*]}" "01-ok 02-broken 03-ok-too"
expect "run_scenario: registra PASS o FAIL según el código de salida" "${RESULT_STATUS[*]}" "PASS FAIL PASS"
expect "run_scenario: solo recoge métricas de los escenarios que terminan bien" "${METRIC_LINES[*]}" "alpha_ms=10 gamma_ms=30"
expect "run_scenario: muestra la salida del escenario en vivo" "$(grep -c 'salida del escenario roto' "$scenario_output")" "1"
expect "run_scenario: duraciones enteras" "$(is_integer "${RESULT_DURATION_MS[0]}")" "yes"
expect "count_passed: cuenta solo los PASS" "$(count_passed)" "2"

summary="$(render_summary)"
expect "render_summary: fila de un escenario roto" "$(grep -c '^02-broken *FAIL' <<<"$summary")" "1"
expect "render_summary: lista las métricas" "$(grep -c '^  alpha_ms=10$' <<<"$summary")" "1"
expect "render_summary: no lista la métrica de un escenario roto" "$(grep -c 'beta_ms' <<<"$summary")" "0"
expect "render_summary: total de escenarios OK" "$(grep -c '^2 de 3 escenarios OK$' <<<"$summary")" "1"
run_report="$(render_run_report 2026-10-12T10:00:00Z)"
expect "render_run_report: resultado global" "$(grep -c 'Resultado:\*\* 2 de 3 escenarios OK' <<<"$run_report")" "1"
expect "render_run_report: fila de tabla" "$(grep -c '^| 02-broken | FAIL | ' <<<"$run_report")" "1"
expect "render_run_report: métrica en tabla" "$(grep -c '^| gamma_ms | 30 |$' <<<"$run_report")" "1"
rm -rf "$fake_dir" "$scenario_output"
RESULT_NAMES=(); RESULT_STATUS=(); RESULT_DURATION_MS=(); METRIC_LINES=()

# --- Argumentos de run-all.sh y de 11-latency.sh (fallan antes de tocar el stack) ---
e2e_dir="$(dirname "${BASH_SOURCE[0]}")"
"$e2e_dir/run-all.sh" --demo >/dev/null 2>&1
expect "run-all.sh: rechaza --demo (código 2)" "$?" "2"
"$e2e_dir/run-all.sh" --report >/dev/null 2>&1
expect "run-all.sh: --report sin archivo (código 2)" "$?" "2"
"$e2e_dir/run-all.sh" --otra >/dev/null 2>&1
expect "run-all.sh: argumento desconocido (código 2)" "$?" "2"
LATENCY_SALES=abc "$e2e_dir/11-latency.sh" >/dev/null 2>&1
expect "11-latency.sh: LATENCY_SALES inválido falla antes de tocar el stack (código 1)" "$?" "1"
expect "11-latency.sh: el mensaje nombra la variable" \
  "$(LATENCY_SALES=abc "$e2e_dir/11-latency.sh" 2>&1 | grep -c 'LATENCY_SALES')" "1"
LATENCY_P95_LIMIT_MS=0 "$e2e_dir/11-latency.sh" >/dev/null 2>&1
expect "11-latency.sh: LATENCY_P95_LIMIT_MS inválido falla (código 1)" "$?" "1"
expect "11-latency.sh: el mensaje nombra LATENCY_P95_LIMIT_MS" \
  "$(LATENCY_P95_LIMIT_MS=0 "$e2e_dir/11-latency.sh" 2>&1 | grep -c 'LATENCY_P95_LIMIT_MS')" "1"
# Distingue la validación del fallo de require_stack (sin Docker también termina con 1 y dice "no responde")
expect "11-latency.sh: LATENCY_SALES inválido no llega a require_stack" \
  "$(LATENCY_SALES=abc "$e2e_dir/11-latency.sh" 2>&1 | grep -c 'no responde')" "0"
expect "11-latency.sh: LATENCY_P95_LIMIT_MS inválido no llega a require_stack" \
  "$(LATENCY_P95_LIMIT_MS=0 "$e2e_dir/11-latency.sh" 2>&1 | grep -c 'no responde')" "0"

if (( failures > 0 )); then
  echo "Autoprueba FALLÓ ($failures)"
  exit 1
fi
echo "Autoprueba OK"
