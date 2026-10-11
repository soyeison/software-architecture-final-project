#!/usr/bin/env bash
# Verifica la infraestructura de docker-compose.yml (spec de infraestructura, sección 7).
# Solo requiere Docker, curl y jq. Dura un par de minutos por los arranques de RabbitMQ.
set -uo pipefail   # sin -e a propósito: se ejecutan todos los checks y se agrega el resultado

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT" || exit 1
command -v curl >/dev/null && command -v jq >/dev/null || { echo "FAIL: se requieren curl y jq en el equipo"; exit 1; }

# Proyecto y puertos propios: no toca ni choca con un stack de desarrollo en marcha.
export COMPOSE_PROJECT_NAME="retail-verify"
export RABBITMQ_AMQP_PORT=35672 RABBITMQ_MGMT_PORT=35673 INVENTORY_DB_PORT=35441 CATALOG_DB_PORT=35442
# Las variables del entorno tienen prioridad sobre el .env de quien desarrolla.
export INVENTORY_DB_PASSWORD="inventory-demo" CATALOG_DB_PASSWORD="catalog-demo"
# Prefijo de las redes de Compose (<proyecto>_<red>).
NET_PREFIX="${COMPOSE_PROJECT_NAME}_"

status=0

cleanup() { docker compose down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

check() { # check <descripción> <valor obtenido> <valor esperado>
  if [[ "$2" == "$3" ]]; then echo "PASS: $1"; else echo "FAIL: $1 (obtenido: $2, esperado: $3)"; status=1; fi
}

compose_defaults() { # compose resuelto con un archivo de entorno dado y sin variables heredadas
  env -u RABBITMQ_AMQP_PORT -u RABBITMQ_MGMT_PORT -u INVENTORY_DB_PORT -u CATALOG_DB_PORT \
      -u INVENTORY_DB_PASSWORD -u CATALOG_DB_PASSWORD \
      docker compose --env-file "$1" config
}

echo "== 1. Archivo válido =="
if docker compose config -q; then echo "PASS: docker compose config es válido"; else echo "FAIL: docker compose config"; exit 1; fi
if diff <(compose_defaults /dev/null) <(compose_defaults .env.example) >/dev/null; then
  echo "PASS: .env.example coincide con los valores por defecto del Compose"
else
  echo "FAIL: .env.example difiere de los valores por defecto del Compose"; status=1
fi

echo "== 2. Arranque y puertos =="
if up_output="$(docker compose up -d --wait --wait-timeout 180 2>&1)"; then
  echo "PASS: los tres servicios llegan a healthy"
else
  echo "FAIL: los servicios no llegaron a healthy"; echo "$up_output"; docker compose ps; exit 1
fi
for target in "rabbitmq 5672" "rabbitmq 15672" "inventory-db 5432" "catalog-db 5432"; do
  # shellcheck disable=SC2086
  published="$(docker compose port $target)"
  check "puerto ${target#* } de ${target% *} publicado solo en 127.0.0.1" "${published%%:*}" "127.0.0.1"
done
# down -v (reinicio de la demo) también borraría volúmenes con nombre: el Compose no debe tener ninguno.
check "el Compose no declara volúmenes con nombre" "$(docker compose config --format json | jq '.volumes // {} | length')" "0"

# Helpers de las secciones 3 a 5
PG_IMAGE="postgres:17-alpine"
RABBIT_API="http://127.0.0.1:${RABBITMQ_MGMT_PORT}/api"
RABBIT_AUTH="retail:retail-demo"
QUEUE="catalog.inventory-stock-changed"

rabbit_api() { curl -s --max-time 10 -u "$RABBIT_AUTH" -H "content-type: application/json" "$@"; }
pg_select_1() { # pg_select_1 <red> <host> <usuario> <contraseña> <base>: imprime 1 o "error" (autentica por TCP)
  docker run --rm --network "$1" -e PGPASSWORD="$4" "$PG_IMAGE" \
    psql -h "$2" -U "$3" -d "$5" -tAc 'select 1' 2>/dev/null || echo "error"
}
resolves() { # resolves <red> <nombre>: imprime resolves o unresolved
  if docker run --rm --network "$1" "$PG_IMAGE" getent hosts "$2" >/dev/null 2>&1; then echo "resolves"; else echo "unresolved"; fi
}

echo "== 3. Credenciales =="
check "inventory acepta su usuario"        "$(pg_select_1 "${NET_PREFIX}inventory-net" inventory-db inventory inventory-demo inventory)" "1"
check "catalog acepta su usuario"          "$(pg_select_1 "${NET_PREFIX}catalog-net" catalog-db catalog catalog-demo catalog)" "1"
check "inventory rechaza una contraseña incorrecta" "$(pg_select_1 "${NET_PREFIX}inventory-net" inventory-db inventory incorrecta inventory)" "error"
check "inventory rechaza al usuario de catalog" "$(pg_select_1 "${NET_PREFIX}inventory-net" inventory-db catalog catalog-demo inventory)" "error"
check "catalog rechaza al usuario de inventory" "$(pg_select_1 "${NET_PREFIX}catalog-net" catalog-db inventory inventory-demo catalog)" "error"

echo "== 4. Aislamiento de redes =="
check "catalog-net resuelve catalog-db"      "$(resolves "${NET_PREFIX}catalog-net" catalog-db)" "resolves"
check "catalog-net NO resuelve inventory-db" "$(resolves "${NET_PREFIX}catalog-net" inventory-db)" "unresolved"
check "inventory-net resuelve inventory-db"  "$(resolves "${NET_PREFIX}inventory-net" inventory-db)" "resolves"
check "inventory-net NO resuelve catalog-db" "$(resolves "${NET_PREFIX}inventory-net" catalog-db)" "unresolved"
check "messaging resuelve rabbitmq"          "$(resolves "${NET_PREFIX}messaging" rabbitmq)" "resolves"
check "messaging NO resuelve inventory-db"   "$(resolves "${NET_PREFIX}messaging" inventory-db)" "unresolved"
check "messaging NO resuelve catalog-db"     "$(resolves "${NET_PREFIX}messaging" catalog-db)" "unresolved"

echo "== 5. Topología de RabbitMQ cargada =="
check "la consola de RabbitMQ responde" \
  "$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -u "$RABBIT_AUTH" "$RABBIT_API/overview")" "200"
check "exchange inventory.events (topic, durable)" \
  "$(rabbit_api "$RABBIT_API/exchanges/%2F/inventory.events" | jq -r '"\(.type) \(.durable)"')" "topic true"
check "exchange catalog.dlx (direct, durable)" \
  "$(rabbit_api "$RABBIT_API/exchanges/%2F/catalog.dlx" | jq -r '"\(.type) \(.durable)"')" "direct true"
check "cola $QUEUE (durable)" \
  "$(rabbit_api "$RABBIT_API/queues/%2F/$QUEUE" | jq -r '.durable')" "true"
check "cola $QUEUE.dlq (durable)" \
  "$(rabbit_api "$RABBIT_API/queues/%2F/$QUEUE.dlq" | jq -r '.durable')" "true"

# Helpers de las secciones 6 a 8
queue_messages() { # mensajes de la cola, exacto e inmediato (la API HTTP actualiza sus estadísticas cada ~5 s)
  docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages | awk -v q="$QUEUE" '$1 == q { print $2 }'
}
wait_for_value() { # wait_for_value <segundos> <esperado> <comando...>: imprime el último valor leído
  local seconds="$1" expected="$2" value=""; shift 2
  for _ in $(seq 1 "$seconds"); do
    value="$("$@" 2>/dev/null)"
    [[ "$value" == "$expected" ]] && break
    sleep 1
  done
  echo "$value"
}
inventory_sql() { docker compose exec -T inventory-db psql -U inventory -d inventory -tAq "$@"; }

echo "== 6. Un mensaje persistente sobrevive a stop/start de RabbitMQ =="
check "se publica un mensaje persistente y se enruta" \
  "$(rabbit_api -X POST "$RABBIT_API/exchanges/%2F/inventory.events/publish" \
      -d '{"properties":{"delivery_mode":2},"routing_key":"inventory.stock.changed","payload":"{}","payload_encoding":"string"}' | jq -r .routed)" "true"
check "el mensaje está en la cola antes de detener RabbitMQ" "$(wait_for_value 10 1 queue_messages)" "1"
docker compose stop rabbitmq >/dev/null 2>&1
docker compose start rabbitmq >/dev/null 2>&1
docker compose up -d --wait --wait-timeout 120 rabbitmq >/dev/null 2>&1
check "el mensaje sigue en la cola tras stop/start" "$(wait_for_value 30 1 queue_messages)" "1"

echo "== 7. Una fila sobrevive a stop/start de la base =="
inventory_sql -c 'create table verify_probe(id int); insert into verify_probe values (1);' >/dev/null 2>&1
check "la fila existe antes de detener la base" "$(inventory_sql -c 'select count(*) from verify_probe' 2>/dev/null)" "1"
docker compose stop inventory-db >/dev/null 2>&1
docker compose start inventory-db >/dev/null 2>&1
docker compose up -d --wait --wait-timeout 120 inventory-db >/dev/null 2>&1
check "la fila sigue tras stop/start" "$(wait_for_value 30 1 inventory_sql -c 'select count(*) from verify_probe')" "1"

echo "== 8. down + up reinicia el estado =="
docker compose down -v >/dev/null 2>&1
docker compose up -d --wait --wait-timeout 180 >/dev/null 2>&1
check "la tabla de prueba ya no existe" "$(inventory_sql -c "select coalesce(to_regclass('verify_probe')::text, 'none')" 2>/dev/null)" "none"
check "la cola quedó vacía" "$(wait_for_value 20 0 queue_messages)" "0"

exit $status
