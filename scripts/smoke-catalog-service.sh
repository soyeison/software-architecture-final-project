#!/usr/bin/env bash
# Prueba de humo del flujo completo venta -> Catálogo dentro de Compose (cierre de la fase 5, spec del
# Catálogo §7): una venta en Inventario aparece en la consulta del Catálogo en pocos segundos con su
# lastEventLatencyMs, las colas quedan vacías (sin mensajes en la DLQ) y el contenedor del Catálogo no corre
# como root. Requiere Docker, curl y jq; la primera vez construye las imágenes (internet y unos minutos).
set -uo pipefail   # sin -e a propósito: se ejecutan todos los checks y se agrega el resultado

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1
command -v curl >/dev/null && command -v jq >/dev/null || { echo "FAIL: se requieren curl y jq en el equipo"; exit 1; }

# Proyecto y puertos propios: no toca ni choca con un stack de desarrollo ni con otras pruebas de humo.
export COMPOSE_PROJECT_NAME="retail-smoke-catalog"
export RABBITMQ_AMQP_PORT=37672 RABBITMQ_MGMT_PORT=37673 INVENTORY_DB_PORT=37441 CATALOG_DB_PORT=37442
export INVENTORY_SERVICE_PORT=37081 CATALOG_SERVICE_PORT=37082
export INVENTORY_DB_PASSWORD="inventory-demo" CATALOG_DB_PASSWORD="catalog-demo"
INVENTORY_URL="http://127.0.0.1:${INVENTORY_SERVICE_PORT}"
CATALOG_URL="http://127.0.0.1:${CATALOG_SERVICE_PORT}"
QUEUE="catalog.inventory-stock-changed"
DEAD_LETTER_QUEUE="catalog.inventory-stock-changed.dlq"
SKU="TENIS-RUNNER-42"

status=0
cleanup() { docker compose down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

check() { # check <descripción> <valor obtenido> <valor esperado>
  if [[ "$2" == "$3" ]]; then echo "PASS: $1"; else echo "FAIL: $1 (obtenido: $2, esperado: $3)"; status=1; fi
}

queue_field() { # queue_field <cola> <columna: messages|consumers>
  docker compose exec -T rabbitmq rabbitmqctl -q list_queues name "$2" | awk -v q="$1" '$1 == q {print $2}' | tr -d '\r'
}

echo "== Arranque =="
if up_output="$(docker compose up -d --wait --wait-timeout 300 --build inventory-service catalog-service 2>&1)"; then
  echo "PASS: inventory-service y catalog-service llegan a healthy"
else
  echo "FAIL: los servicios no llegaron a healthy"; echo "$up_output"
  docker compose logs --tail 50 inventory-service catalog-service; exit 1
fi
container_user="$(docker compose exec -T catalog-service id -u | tr -d '\r')"
check "el contenedor del Catálogo no corre como root" "$([[ "$container_user" =~ ^[0-9]+$ && "$container_user" != "0" ]] && echo yes || echo no)" "yes"
check "el Catálogo tiene un consumidor en la cola $QUEUE" "$(queue_field "$QUEUE" consumers)" "1"

echo "== Estado inicial =="
stock_before="$(curl -s --max-time 10 "$CATALOG_URL/api/v1/catalog/products/$SKU" | jq -r '.availableStock')"
check "el Catálogo sirve $SKU con la semilla" "$stock_before" "5"

echo "== Venta =="
token="$("$ROOT/scripts/generate-token.sh" pos)"
http_status="$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' -X POST "$INVENTORY_URL/api/v1/inventory/$SKU/sales" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d '{"quantity":1,"channel":"POS"}')"
check "la venta responde 201" "$http_status" "201"

echo "== Propagación al Catálogo =="
stock_after="$stock_before"; latency="null"
for _ in $(seq 1 30); do
  product="$(curl -s --max-time 10 "$CATALOG_URL/api/v1/catalog/products/$SKU")"
  stock_after="$(jq -r '.availableStock' <<<"$product")"
  latency="$(jq -r '.lastEventLatencyMs' <<<"$product")"
  [[ "$stock_after" == "$((stock_before - 1))" ]] && break
  sleep 1
done
check "el Catálogo refleja la venta (stock $((stock_before - 1)))" "$stock_after" "$((stock_before - 1))"
check "lastEventLatencyMs se informa" "$([[ "$latency" =~ ^[0-9]+$ ]] && echo yes || echo no)" "yes"
echo "INFO: latencia venta -> Catálogo: ${latency} ms"

echo "== Colas =="
check "la cola $QUEUE queda vacía" "$(queue_field "$QUEUE" messages)" "0"
check "la DLQ $DEAD_LETTER_QUEUE queda vacía" "$(queue_field "$DEAD_LETTER_QUEUE" messages)" "0"

[[ $status -eq 0 ]] && echo "Prueba de humo OK" || echo "Prueba de humo FALLÓ"
exit $status
