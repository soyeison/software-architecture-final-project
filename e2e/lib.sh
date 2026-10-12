#!/usr/bin/env bash
# Funciones comunes de los escenarios E2E (spec E2E §2). Se carga con `source`; no ejecuta nada por sí sola
# y no contiene lógica de escenarios. Requiere bash, curl, jq y Docker Compose.

E2E_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INVENTORY_URL="${INVENTORY_URL:-http://127.0.0.1:${INVENTORY_SERVICE_PORT:-8081}}"
CATALOG_URL="${CATALOG_URL:-http://127.0.0.1:${CATALOG_SERVICE_PORT:-8082}}"
WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-30}"
QUEUE="catalog.inventory-stock-changed"
DEMO="${DEMO:-0}"

# Estado acumulado del escenario: 0 mientras todas las comprobaciones pasen.
E2E_STATUS=0
# Resultado de la última venta (ver `sell`) y del último `wait_until` (milisegundos esperados).
SELL_STATUS=""
SELL_BODY=""
WAITED_MS=0

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

# synced <sku>: éxito si el Catálogo muestra el mismo stock que Inventario.
synced() { [[ "$(catalog_stock "$1")" == "$(inventory_stock "$1")" ]]; }

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
