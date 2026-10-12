#!/usr/bin/env bash
# Funciones comunes de los escenarios E2E (spec E2E §2). Se carga con `source`; no ejecuta nada por sí sola
# y no contiene lógica de escenarios. Requiere bash, curl, jq y Docker Compose.

E2E_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INVENTORY_URL="${INVENTORY_URL:-http://127.0.0.1:${INVENTORY_SERVICE_PORT:-8081}}"
CATALOG_URL="${CATALOG_URL:-http://127.0.0.1:${CATALOG_SERVICE_PORT:-8082}}"
WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-30}"
QUEUE="catalog.inventory-stock-changed"
DEAD_LETTER_QUEUE="catalog.inventory-stock-changed.dlq"
EVENT_EXCHANGE="inventory.events"
EVENT_ROUTING_KEY="inventory.stock.changed"
RABBITMQ_MANAGEMENT_URL="${RABBITMQ_MANAGEMENT_URL:-http://127.0.0.1:${RABBITMQ_MGMT_PORT:-15672}}"
RABBITMQ_USER="${RABBITMQ_USER:-retail}"
RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD:-retail-demo}"
DEMO="${DEMO:-0}"

# Estado acumulado del escenario: 0 mientras todas las comprobaciones pasen.
E2E_STATUS=0
# Resultado de la última venta (ver `sell`) y del último `wait_until` (milisegundos esperados).
SELL_STATUS=""
SELL_BODY=""
WAITED_MS=0
# eventId del último evento inyectado con publish_event.
LAST_EVENT_ID=""

# Ejecuta docker compose desde la raíz del repositorio (el stack de desarrollo, proyecto por defecto).
compose() { (cd "$E2E_ROOT" && docker compose "$@"); }

# Procesa los argumentos comunes de todos los escenarios: --demo (narración paso a paso).
e2e_init() {
  for argument in "$@"; do
    case "$argument" in
      --demo) DEMO=1 ;;
      *) echo "Uso: $0 [--demo]" >&2; exit 2 ;;
    esac
  done
}

# check <descripción> <valor obtenido> <valor esperado>
check() {
  if [[ "$2" == "$3" ]]; then
    echo "PASS: $1"
  else
    echo "FAIL: $1 (obtenido: $2, esperado: $3)"
    E2E_STATUS=1
  fi
}

# is_integer <valor>: imprime yes o no.
is_integer() { [[ "$1" =~ ^[0-9]+$ ]] && echo yes || echo no; }

# wait_until <descripción> <comando...>: reintenta cada 200 ms hasta WAIT_TIMEOUT_SECONDS. Deja los
# milisegundos esperados en WAITED_MS; imprime PASS o FAIL y devuelve 0 o 1.
wait_until() {
  local description="$1"
  shift
  local start_ns now_ns
  start_ns="$(date +%s%N)"
  while true; do
    if "$@" >/dev/null 2>&1; then
      now_ns="$(date +%s%N)"
      WAITED_MS=$(( (now_ns - start_ns) / 1000000 ))
      echo "PASS: $description (${WAITED_MS} ms)"
      return 0
    fi
    now_ns="$(date +%s%N)"
    WAITED_MS=$(( (now_ns - start_ns) / 1000000 ))
    if (( WAITED_MS >= WAIT_TIMEOUT_SECONDS * 1000 )); then
      echo "FAIL: $description (no ocurrió en ${WAIT_TIMEOUT_SECONDS} s)"
      E2E_STATUS=1
      return 1
    fi
    sleep 0.2
  done
}

# step <texto>: narra un paso. Con --demo muestra el texto destacado y espera Enter.
step() {
  if [[ "$DEMO" == "1" ]]; then
    printf '\n\033[1;36m▶ %s\033[0m\n' "$1"
    read -r -p "   (Enter para continuar) " _ || true
  else
    echo "-- $1"
  fi
}

# show <texto>: en modo demo muestra una petición o respuesta; si no, no imprime nada.
show() { [[ "$DEMO" == "1" ]] && printf '   %s\n' "$1"; return 0; }

# http_status <url>: código HTTP de un GET sin autenticación.
http_status() { curl -s --max-time 10 -o /dev/null -w '%{http_code}' "$1"; }

# token <pos|online|operator|no-scope>
token() { "$E2E_ROOT/scripts/generate-token.sh" "$1"; }

# sell <sku> <POS|ONLINE> [token] [cantidad]: registra una venta. Sin tercer argumento usa el token del
# canal; con "" no envía Authorization. Deja el código HTTP en SELL_STATUS y el cuerpo en SELL_BODY.
sell() {
  local sku="$1" channel="$2" quantity="${4:-1}" bearer
  if (( $# >= 3 )); then
    bearer="$3"
  elif [[ "$channel" == "POS" ]]; then
    bearer="$(token pos)"
  else
    bearer="$(token online)"
  fi
  local -a authorization=()
  [[ -n "$bearer" ]] && authorization=(-H "Authorization: Bearer $bearer")
  local body_file
  body_file="$(mktemp)"
  show "POST $INVENTORY_URL/api/v1/inventory/$sku/sales  {\"quantity\":$quantity,\"channel\":\"$channel\"}"
  SELL_STATUS="$(curl -s --max-time 10 -o "$body_file" -w '%{http_code}' -X POST \
    "$INVENTORY_URL/api/v1/inventory/$sku/sales" "${authorization[@]}" \
    -H 'Content-Type: application/json' -d "{\"quantity\":$quantity,\"channel\":\"$channel\"}")"
  SELL_BODY="$(cat "$body_file")"
  rm -f "$body_file"
  show "← $SELL_STATUS $SELL_BODY"
}

# catalog_product <sku>: JSON de la consulta del Catálogo.
catalog_product() { curl -s --max-time 10 "$CATALOG_URL/api/v1/catalog/products/$1"; }
# catalog_field <sku> <filtro jq>: un campo de la consulta del Catálogo.
catalog_field() { catalog_product "$1" | jq -r "$2"; }
catalog_stock() { catalog_field "$1" '.availableStock'; }
# catalog_stock_is <sku> <stock>: éxito si el Catálogo muestra ese stock (para wait_until).
catalog_stock_is() { [[ "$(catalog_stock "$1")" == "$2" ]]; }

# inventory_stock <sku>: stock actual según Inventario (token de operador).
inventory_stock() {
  curl -s --max-time 10 -H "Authorization: Bearer $(token operator)" "$INVENTORY_URL/api/v1/inventory/$1" | jq -r '.stock'
}

# outbox_count <sku>: eventos del SKU entre los 50 más recientes del outbox.
outbox_count() {
  curl -s --max-time 10 "$INVENTORY_URL/api/v1/inventory/outbox" | jq --arg sku "$1" '[.[] | select(.sku == $sku)] | length'
}
# outbox_count_is <sku> <cantidad> (para wait_until)
outbox_count_is() { [[ "$(outbox_count "$1")" == "$2" ]]; }
# outbox_event_published <eventId>: éxito si el evento figura PUBLISHED.
outbox_event_published() {
  curl -s --max-time 10 "$INVENTORY_URL/api/v1/inventory/outbox" \
    | jq -e --arg id "$1" 'any(.[]; .eventId == $id and .status == "PUBLISHED")' >/dev/null
}

# queue_messages <cola>: mensajes de una cola con rabbitmqctl (valor exacto, sin el retraso de la consola).
queue_messages() {
  compose exec -T rabbitmq rabbitmqctl -q list_queues name messages | awk -v q="$1" '$1 == q {print $2}' | tr -d '\r'
}

# queue_is_empty <cola>: éxito si la cola no tiene mensajes (para wait_until).
queue_is_empty() { [[ "$(queue_messages "$1")" == "0" ]]; }

# queue_messages_is <cola> <cantidad> (para wait_until)
queue_messages_is() { [[ "$(queue_messages "$1")" == "$2" ]]; }

# --- Inyección de eventos en RabbitMQ (por la API de gestión) y logs del Catálogo ---

# new_uuid: UUID aleatorio (Linux).
new_uuid() { cat /proc/sys/kernel/random/uuid; }

# version_base: base para las versiones de los eventos inyectados, a partir del reloj (milisegundos desde la
# época). Crece siempre, así que supera cualquier versión ya aplicada y el escenario se puede repetir sin reset.
version_base() { date +%s%3N; }

# build_event <eventId> <sku> <stock> <versión> [canal]: JSON de un evento InventoryStockChanged válido,
# con occurredAt = ahora (UTC, milisegundos).
build_event() {
  local occurred_at
  occurred_at="$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)"
  jq -cn --arg id "$1" --arg sku "$2" --argjson stock "$3" --argjson version "$4" \
    --arg channel "${5:-POS}" --arg at "$occurred_at" \
    '{eventId: $id, eventType: "InventoryStockChanged", sku: $sku, newStock: $stock,
      stockVersion: $version, channel: $channel, occurredAt: $at}'
}

# management_publish_body <cuerpo>: cuerpo de la petición de publicación de la API de gestión de RabbitMQ.
management_publish_body() {
  jq -cn --arg payload "$1" --arg key "$EVENT_ROUTING_KEY" \
    '{properties: {content_type: "application/json", delivery_mode: 2}, routing_key: $key,
      payload: $payload, payload_encoding: "string"}'
}

# publish_raw <cuerpo>: publica el cuerpo tal cual en el exchange de eventos. Devuelve 0 solo si el broker
# lo enruta a una cola (routed=true).
publish_raw() {
  local routed
  show "PUBLISH $EVENT_EXCHANGE / $EVENT_ROUTING_KEY  $1"
  routed="$(curl -s --max-time 10 -u "$RABBITMQ_USER:$RABBITMQ_PASSWORD" -H 'Content-Type: application/json' \
    -X POST "$RABBITMQ_MANAGEMENT_URL/api/exchanges/%2F/$EVENT_EXCHANGE/publish" \
    -d "$(management_publish_body "$1")" | jq -r '.routed')"
  [[ "$routed" == "true" ]]
}

# publish_event <sku> <stock> <versión> [eventId] [canal]: publica un evento válido. Deja su eventId en
# LAST_EVENT_ID (nuevo si no se indica). Devuelve 0 si el broker lo enruta.
publish_event() {
  LAST_EVENT_ID="${4:-$(new_uuid)}"
  local body
  # Si el evento no se puede construir (stock o versión no numéricos) no se publica nada.
  body="$(build_event "$LAST_EVENT_ID" "$1" "$2" "$3" "${5:-POS}")" || return 1
  publish_raw "$body"
}

# catalog_log_count <patrón>: líneas del log del Catálogo que contienen el patrón (texto literal).
# Si no se pueden leer los logs (stack caído, servicio inexistente) devuelve 1 sin imprimir nada, para que
# un "0 coincidencias" nunca pueda ser un falso positivo.
catalog_log_count() {
  local logs
  logs="$(compose logs --no-color catalog-service 2>/dev/null)" || return 1
  # grep -c imprime 0 y devuelve 1 cuando no hay coincidencias: no es un fallo.
  grep -c -F -- "$1" <<<"$logs" || true
}
# catalog_log_count_is <patrón> <cantidad> (para wait_until)
catalog_log_count_is() { [[ "$(catalog_log_count "$1")" == "$2" ]]; }

# --- Caídas de servicios (escenarios 05 y 08) y métricas ---

# Servicios detenidos por el escenario y aún no restaurados (ver stop_service y restore_services).
STOPPED_SERVICES=()

# now_ms: milisegundos desde la época (para medir tiempos).
now_ms() { date +%s%3N; }

# metric <nombre> <valor>: imprime una métrica con un formato fijo que run-all.sh puede recoger.
metric() { echo "METRIC $1=$2"; }

# stop_service <servicio>: detiene un contenedor del Compose y lo registra para restaurarlo al salir.
stop_service() {
  compose stop "$1" >/dev/null 2>&1 || return 1
  STOPPED_SERVICES+=("$1")
}

# start_service <servicio>: levanta el contenedor, espera a que esté sano y lo quita del registro.
start_service() {
  compose up -d --wait --wait-timeout 120 --no-recreate "$1" >/dev/null 2>&1 || return 1
  local remaining=() service
  for service in "${STOPPED_SERVICES[@]}"; do
    [[ "$service" != "$1" ]] && remaining+=("$service")
  done
  STOPPED_SERVICES=("${remaining[@]}")
}

# restore_services: levanta todo lo que el escenario dejó detenido. Se usa como trap de salida.
restore_services() {
  local pending=("${STOPPED_SERVICES[@]}") service
  for service in "${pending[@]}"; do
    echo "Restaurando $service..." >&2
    start_service "$service" || echo "FAIL: no se pudo restaurar $service; ejecuta e2e/reset.sh" >&2
  done
}

# install_restore_trap: garantiza que los servicios detenidos se levanten aunque el escenario falle o se
# interrumpa (Ctrl+C o señal de terminación).
install_restore_trap() {
  trap restore_services EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
}

# queue_consumers <cola>: consumidores conectados a una cola (rabbitmqctl).
queue_consumers() {
  compose exec -T rabbitmq rabbitmqctl -q list_queues name consumers | awk -v q="$1" '$1 == q {print $2}' | tr -d '\r'
}
# queue_consumers_is <cola> <cantidad> (para wait_until)
queue_consumers_is() { [[ "$(queue_consumers "$1")" == "$2" ]]; }

# outbox_count_with_status <estado> <eventId...>: cuántos de esos eventos tienen ese estado en el outbox.
outbox_count_with_status() {
  local status="$1"
  shift
  curl -s --max-time 10 "$INVENTORY_URL/api/v1/inventory/outbox?status=$status" \
    | jq --argjson ids "$(printf '%s\n' "$@" | jq -R . | jq -sc .)" \
      '[.[] | select(.eventId as $id | $ids | index($id))] | length' 2>/dev/null
}
# outbox_count_with_status_is <estado> <cantidad> <eventId...> (para wait_until)
outbox_count_with_status_is() { [[ "$(outbox_count_with_status "$1" "${@:3}")" == "$2" ]]; }

# catalog_applied_sequence <sku> <eventId...>: de esos eventId, los que el Catálogo aplicó (líneas event_applied del
# SKU), en el orden del log, uno por línea. Devuelve 1 si no puede leer el log.
catalog_applied_sequence() {
  local logs sku="$1"
  shift
  logs="$(compose logs --no-color catalog-service 2>/dev/null)" || return 1
  grep -o -- "event_applied sku=$sku eventId=[0-9a-f-]*" <<<"$logs" | sed 's/.*eventId=//' \
    | grep -Fx -f <(printf '%s\n' "$@") || true
}

# synced <sku>: éxito si el Catálogo muestra el mismo stock que Inventario; ambos deben ser enteros
# (una consulta fallida deja vacío o null y no cuenta como coincidencia).
synced() {
  local catalog inventory
  catalog="$(catalog_stock "$1")"
  inventory="$(inventory_stock "$1")"
  [[ "$(is_integer "$catalog")" == "yes" && "$catalog" == "$inventory" ]]
}

# fail_precondition <mensaje>: termina el escenario sin ejecutarlo.
fail_precondition() {
  echo "FAIL: $1"
  echo "      Estado no inicial: ejecuta e2e/reset.sh y vuelve a intentarlo."
  exit 1
}

# require_stack: herramientas instaladas y ambos servicios respondiendo.
require_stack() {
  local tool
  for tool in docker curl jq; do
    command -v "$tool" >/dev/null || { echo "FAIL: se requiere $tool en el equipo"; exit 1; }
  done
  local url
  for url in "$INVENTORY_URL" "$CATALOG_URL"; do
    if ! curl -s -f --max-time 5 -o /dev/null "$url/actuator/health"; then
      echo "FAIL: $url no responde. Levanta el stack con: docker compose up -d --wait"
      exit 1
    fi
  done
}

# require_synced <sku>: espera a que el Catálogo coincida con Inventario antes de empezar.
require_synced() {
  wait_until "el Catálogo está sincronizado con Inventario para $1" synced "$1" \
    || fail_precondition "el Catálogo no coincide con Inventario para $1"
}

# require_stock <sku> <mínimo>: stock suficiente para el escenario.
require_stock() {
  local stock
  stock="$(inventory_stock "$1")"
  [[ "$stock" =~ ^[0-9]+$ ]] && (( stock >= $2 )) \
    || fail_precondition "$1 tiene stock $stock y el escenario necesita al menos $2"
}

# statistics <valores...>: deja STAT_MIN, STAT_AVERAGE (un decimal), STAT_P95 (rango más cercano) y STAT_MAX.
# Devuelve 1 si no recibe valores.
statistics() {
  (( $# > 0 )) || return 1
  local summary
  summary="$(printf '%s\n' "$@" | sort -n | awk '
    { values[NR] = $1; sum += $1 }
    END {
      rank = int(0.95 * NR); if (rank < 0.95 * NR) rank++
      printf "%s %.1f %s %s", values[1], sum / NR, values[rank], values[NR]
    }')"
  read -r STAT_MIN STAT_AVERAGE STAT_P95 STAT_MAX <<<"$summary"
}

# finish <nombre del escenario>: imprime el resultado y termina con el código acumulado.
finish() {
  if [[ $E2E_STATUS -eq 0 ]]; then echo "Escenario OK: $1"; else echo "Escenario FALLÓ: $1"; fi
  exit "$E2E_STATUS"
}
