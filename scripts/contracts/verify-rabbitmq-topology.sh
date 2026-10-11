#!/usr/bin/env bash
# Arranca RabbitMQ con infra/rabbitmq/definitions.json y verifica la topología de la spec de
# contratos (§6): exchanges, colas, argumentos, enrutamiento y envío a la DLQ.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
RABBIT_DIR="$ROOT/infra/rabbitmq"
IMAGE="rabbitmq:4.3-management"
CONTAINER="contracts-rabbitmq-verify"
AUTH="retail:retail-demo"

for f in definitions.json rabbitmq.conf; do
  if [[ ! -f "$RABBIT_DIR/$f" ]]; then
    echo "FAIL: falta infra/rabbitmq/$f"
    exit 1
  fi
done

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

docker run -d --name "$CONTAINER" -p 127.0.0.1::15672 \
  -v "$RABBIT_DIR/definitions.json:/etc/rabbitmq/definitions.json:ro,z" \
  -v "$RABBIT_DIR/rabbitmq.conf:/etc/rabbitmq/conf.d/20-definitions.conf:ro,z" \
  "$IMAGE" >/dev/null

PORT="$(docker port "$CONTAINER" 15672/tcp | head -1 | sed 's/.*://')"
API="http://127.0.0.1:$PORT/api"

echo "Esperando a RabbitMQ en el puerto $PORT..."
for _ in $(seq 1 60); do
  if curl -sf -u "$AUTH" "$API/overview" >/dev/null 2>&1; then break; fi
  sleep 2
done
if ! curl -sf -u "$AUTH" "$API/overview" >/dev/null 2>&1; then
  echo "FAIL: RabbitMQ no arrancó o el usuario retail no existe"
  docker logs "$CONTAINER" 2>&1 | tail -20
  exit 1
fi

status=0
api() { curl -s -u "$AUTH" -H "content-type: application/json" "$@"; }
check() { # check <descripción> <valor obtenido> <valor esperado>
  if [[ "$2" == "$3" ]]; then echo "PASS: $1"; else echo "FAIL: $1 (obtenido: $2, esperado: $3)"; status=1; fi
}

check "exchange inventory.events es topic y durable" \
  "$(api "$API/exchanges/%2F/inventory.events" | jq -r '"\(.type) \(.durable)"')" "topic true"
check "exchange catalog.dlx es direct y durable" \
  "$(api "$API/exchanges/%2F/catalog.dlx" | jq -r '"\(.type) \(.durable)"')" "direct true"
check "cola catalog.inventory-stock-changed es durable con DLX" \
  "$(api "$API/queues/%2F/catalog.inventory-stock-changed" | jq -r '"\(.durable) \(.arguments["x-queue-type"]) \(.arguments["x-dead-letter-exchange"]) \(.arguments["x-dead-letter-routing-key"])"')" \
  "true classic catalog.dlx catalog.inventory-stock-changed.dlq"
check "DLQ catalog.inventory-stock-changed.dlq es durable" \
  "$(api "$API/queues/%2F/catalog.inventory-stock-changed.dlq" | jq -r '"\(.durable) \(.arguments["x-queue-type"])"')" "true classic"

publish() { # publish <routing key>
  api -X POST "$API/exchanges/%2F/inventory.events/publish" \
    -d "{\"properties\":{},\"routing_key\":\"$1\",\"payload\":\"{}\",\"payload_encoding\":\"string\"}" | jq -r .routed
}
check "routing key inventory.stock.changed llega a la cola" "$(publish inventory.stock.changed)" "true"
check "routing key desconocida no se enruta" "$(publish inventory.stock.changd)" "false"

check "rechazo sin reencolar saca el mensaje de la cola" \
  "$(api -X POST "$API/queues/%2F/catalog.inventory-stock-changed/get" \
      -d '{"count":1,"ackmode":"reject_requeue_false","encoding":"auto"}' | jq length)" "1"
dlq_count=0
for _ in $(seq 1 10); do
  dlq_count="$(api -X POST "$API/queues/%2F/catalog.inventory-stock-changed.dlq/get" \
      -d '{"count":1,"ackmode":"ack_requeue_false","encoding":"auto"}' | jq length)"
  [[ "$dlq_count" == "1" ]] && break
  sleep 1
done
check "mensaje rechazado llega a la DLQ" "$dlq_count" "1"

check "usuario guest no existe" \
  "$(api "$API/users" | jq -r '[.[].name] | index("guest")')" "null"

exit $status
