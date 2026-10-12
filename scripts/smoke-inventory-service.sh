#!/usr/bin/env bash
# Prueba de humo del Servicio de Inventario dentro de Compose (cierre de la fase 4, spec de Inventario §9):
# una venta devuelve 201, su evento pasa a PUBLISHED en el outbox y el mensaje aparece en la cola del
# Catálogo. También comprueba que el contenedor no corre como root. Requiere Docker, curl y jq; la
# primera vez construye la imagen (internet y unos minutos).
# Mientras el Catálogo no consuma la cola (fase 5), el mensaje permanece en ella; cuando exista, la
# comprobación de la cola dependerá de que el Catálogo esté detenido.
set -uo pipefail   # sin -e a propósito: se ejecutan todos los checks y se agrega el resultado

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1
command -v curl >/dev/null && command -v jq >/dev/null || { echo "FAIL: se requieren curl y jq en el equipo"; exit 1; }

# Proyecto y puertos propios: no toca ni choca con un stack de desarrollo en marcha.
export COMPOSE_PROJECT_NAME="retail-smoke"
export RABBITMQ_AMQP_PORT=36672 RABBITMQ_MGMT_PORT=36673 INVENTORY_DB_PORT=36441 CATALOG_DB_PORT=36442 INVENTORY_SERVICE_PORT=36081
export INVENTORY_DB_PASSWORD="inventory-demo" CATALOG_DB_PASSWORD="catalog-demo"
BASE_URL="http://127.0.0.1:${INVENTORY_SERVICE_PORT}"
QUEUE="catalog.inventory-stock-changed"
SKU="GORRA-SPORT-NEGRA"

status=0
cleanup() { docker compose down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

check() { # check <descripción> <valor obtenido> <valor esperado>
  if [[ "$2" == "$3" ]]; then echo "PASS: $1"; else echo "FAIL: $1 (obtenido: $2, esperado: $3)"; status=1; fi
}

echo "== Arranque =="
if up_output="$(docker compose up -d --wait --wait-timeout 300 --build inventory-service 2>&1)"; then
  echo "PASS: inventory-service llega a healthy"
else
  echo "FAIL: inventory-service no llegó a healthy"; echo "$up_output"; docker compose logs --tail 50 inventory-service; exit 1
fi
container_user="$(docker compose exec -T inventory-service id -u | tr -d '\r')"
check "el contenedor no corre como root" "$([[ "$container_user" =~ ^[0-9]+$ && "$container_user" != "0" ]] && echo yes || echo no)" "yes"

echo "== Venta =="
token="$("$ROOT/scripts/generate-token.sh" pos)"
response_file="$(mktemp)"
http_status="$(curl -s -o "$response_file" -w '%{http_code}' -X POST "$BASE_URL/api/v1/inventory/$SKU/sales" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d '{"quantity":1,"channel":"POS"}')"
check "la venta responde 201" "$http_status" "201"
sale_id="$(jq -r '.saleId' "$response_file")"
rm -f "$response_file"

echo "== Publicación =="
published="no"
for _ in $(seq 1 30); do
  published="$(curl -s "$BASE_URL/api/v1/inventory/outbox?status=PUBLISHED" \
    | jq -r --arg id "$sale_id" 'any(.[]; .eventId == $id)')"
  [[ "$published" == "true" ]] && break
  sleep 1
done
check "el evento de la venta pasa a PUBLISHED en el outbox" "$published" "true"

queue_messages="$(docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages | awk -v q="$QUEUE" '$1 == q {print $2}' | tr -d '\r')"
check "la cola $QUEUE tiene el mensaje" "$([[ "${queue_messages:-0}" -ge 1 ]] && echo yes || echo no)" "yes"

[[ $status -eq 0 ]] && echo "Prueba de humo OK" || echo "Prueba de humo FALLÓ"
exit $status
