#!/usr/bin/env bash
# Verifica la infraestructura de docker-compose.yml (spec de infraestructura, sección 7).
# Solo requiere Docker, curl y jq. Dura un par de minutos por los arranques de RabbitMQ.
set -uo pipefail   # sin -e a propósito: se ejecutan todos los checks y se agrega el resultado

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT" || exit 1

# Proyecto y puertos propios: no toca ni choca con un stack de desarrollo en marcha.
export COMPOSE_PROJECT_NAME="retail-verify"
export RABBITMQ_AMQP_PORT=35672 RABBITMQ_MGMT_PORT=35673 INVENTORY_DB_PORT=35441 CATALOG_DB_PORT=35442
# Las variables del entorno tienen prioridad sobre el .env de quien desarrolla.
export INVENTORY_DB_PASSWORD="inventory-demo" CATALOG_DB_PASSWORD="catalog-demo"

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

exit $status
