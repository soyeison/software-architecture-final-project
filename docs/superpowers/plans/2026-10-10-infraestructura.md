# Infraestructura (fase 3) — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dejar lista la infraestructura local del sistema: un `docker-compose.yml` con RabbitMQ (topología cargada) y una base PostgreSQL aislada por servicio, con un script que la verifica de punta a punta, y la documentación y los datos semilla de referencia.

**Architecture:** Un solo `docker-compose.yml` en la raíz; las fases 4 y 5 agregarán los servicios a este mismo archivo. Tres redes (`messaging`, `inventory-net`, `catalog-net`) hacen cumplir en la infraestructura que el Catálogo no accede a la base de Inventario. La verificación (`scripts/verify-infra.sh`) se escribe primero, se ve fallar y después se crea el Compose hasta que pasa; cada comprobación se prueba además con una mutación del Compose para demostrar que puede fallar.

**Tech Stack:** Docker Compose, `rabbitmq:4.3-management`, `postgres:17-alpine`, bash, curl, jq.

**Spec:** `docs/superpowers/specs/2026-10-10-infraestructura-design.md` (depende de `docs/superpowers/specs/2026-10-09-solucion-global-design.md` y `docs/superpowers/specs/2026-10-10-contratos-design.md`).

## Global Constraints

- Imágenes fijas, sin `latest`: `rabbitmq:4.3-management` y `postgres:17-alpine` (ambas etiquetas comprobadas).
- Puertos del host publicados solo en `127.0.0.1`: `5672` y `15672` (RabbitMQ), `5441` (`inventory-db`) y `5442` (`catalog-db`); configurables con `RABBITMQ_AMQP_PORT`, `RABBITMQ_MGMT_PORT`, `INVENTORY_DB_PORT` y `CATALOG_DB_PORT`.
- RabbitMQ monta `infra/rabbitmq/definitions.json` en `/etc/rabbitmq/definitions.json` y `infra/rabbitmq/rabbitmq.conf` en `/etc/rabbitmq/conf.d/20-definitions.conf`, ambos con `ro,z` (la opción `z` es necesaria en Fedora por SELinux).
- Credenciales de demo, no aptas para producción: RabbitMQ `retail` / `retail-demo` (no parametrizables en el Compose: están en `definitions.json` como hash); bases `inventory` / `inventory-demo` y `catalog` / `catalog-demo`, configurables por `INVENTORY_DB_PASSWORD` y `CATALOG_DB_PASSWORD`.
- Sin volúmenes con nombre y sin política `restart`.
- Healthchecks: `pg_isready` (con `-h 127.0.0.1`) en las bases y `rabbitmq-diagnostics -q check_running` en RabbitMQ.
- Fuera de alcance (YAGNI): volúmenes con nombre, `restart`, pgAdmin u otro cliente web, `profiles`, TLS en RabbitMQ y un `Makefile`.
- Código en inglés (nombres de servicios, redes, variables y scripts); documentación y comentarios en español.
- Commits en inglés, breves, Conventional Commits; se permite `Co-Authored-By: Claude <modelo> <noreply@anthropic.com>`.
- La ruta del repositorio contiene espacios ("Arquitectura de Software"): toda ruta en los scripts va entre comillas dobles; los scripts calculan la raíz con `git rev-parse --show-toplevel`.
- Esta fase no incluye código ni `Dockerfile` de los servicios.

## Review Focus

Modos de fallo que la spec implica y que se fijan con pruebas en este plan:

1. **PostgreSQL reporta "listo" mientras aún inicializa** (el servidor temporal solo escucha en el socket unix), de modo que un servicio dependiente se conectaría demasiado pronto. Se espera que el healthcheck solo marque *healthy* cuando el servidor acepta conexiones TCP. Se fija con el check 3 (Tarea 2), que se conecta por TCP con contraseña inmediatamente después de `up --wait`.
2. **RabbitMQ *healthy* pero sin topología** (por ejemplo, un montaje sin `:z` bajo SELinux o sin los montajes): el healthcheck pasa y el broker arranca sin el usuario `retail`. Se espera que la verificación lo detecte. Se fija con el check 5 y su mutación (Tarea 2).
3. **Un puerto abierto a todas las interfaces** (`0.0.0.0`). Se espera que la verificación falle. Se fija con los checks de la sección 2 y su mutación (Tarea 1).
4. **La verificación pisa un stack de desarrollo en marcha, o la rompe el `.env` de quien desarrolla** (contraseñas distintas). Se espera que use proyecto y puertos propios y que las variables del script tengan prioridad. Se fija en la Tarea 3.
5. **`.env.example` se desalinea de los valores por defecto del Compose.** Se espera un fallo explícito. Se fija con el check 1 y su mutación (Tarea 1, pasos 5 y 8).

Además, toda ejecución fallida debe dejar cero contenedores del proyecto `retail-verify` (se comprueba tras cada mutación).

---

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `docker-compose.yml` | RabbitMQ, `inventory-db`, `catalog-db`, sus healthchecks y las tres redes |
| `.env.example` | Variables opcionales con sus valores por defecto |
| `scripts/verify-infra.sh` | Verificación de la infraestructura (8 secciones de la spec) |
| `docs/seed-data.md` | Datos semilla compartidos por los servicios y los E2E |
| `README.md` | Sección "Infraestructura" y árbol de carpetas |
| `CLAUDE.md` | Comandos útiles del proyecto |
| `docs/superpowers/specs/2026-10-09-solucion-global-design.md` | Se corrigen §3.5 y §3.8 |

---

### Task 1: Compose, `.env.example` y verificación de arranque

**Files:**
- Create: `scripts/verify-infra.sh`
- Create: `docker-compose.yml`
- Create: `.env.example`

**Interfaces:**
- Consumes: `infra/rabbitmq/definitions.json` e `infra/rabbitmq/rabbitmq.conf` (fase 2).
- Produces: `docker-compose.yml` con los servicios `rabbitmq`, `inventory-db`, `catalog-db`, las redes `messaging`, `inventory-net`, `catalog-net` y el nombre de proyecto por defecto `retail`; `scripts/verify-infra.sh` con las secciones 1 y 2 y la línea final `exit $status` (las tareas 2 y 3 insertan bloques justo antes de esa línea); funciones `check`, `compose_defaults` y `cleanup` y las variables exportadas del proyecto de verificación (`COMPOSE_PROJECT_NAME=retail-verify`, puertos `35672`, `35673`, `35441`, `35442`).

- [ ] **Step 1: Escribir el script de verificación (secciones 1 y 2)**

`scripts/verify-infra.sh`:

```bash
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
```

Run: `chmod +x scripts/verify-infra.sh`

- [ ] **Step 2: Ejecutar el script y verificar que falla**

Run: `scripts/verify-infra.sh; echo "exit=$?"`
Expected: un error de Compose indicando que no hay archivo de configuración, la línea `FAIL: docker compose config` y `exit=1`.

- [ ] **Step 3: Escribir el Compose**

`docker-compose.yml`:

```yaml
# Infraestructura del sistema: RabbitMQ y una base PostgreSQL por servicio.
# Credenciales de DEMO: no aptas para producción. Los servicios (fases 4 y 5) se agregan a este archivo.
name: retail

services:
  rabbitmq:
    image: rabbitmq:4.3-management
    ports:
      - "127.0.0.1:${RABBITMQ_AMQP_PORT:-5672}:5672"
      - "127.0.0.1:${RABBITMQ_MGMT_PORT:-15672}:15672"
    volumes:
      # La opción z es necesaria en Fedora (SELinux); en otros sistemas no tiene efecto.
      - ./infra/rabbitmq/definitions.json:/etc/rabbitmq/definitions.json:ro,z
      - ./infra/rabbitmq/rabbitmq.conf:/etc/rabbitmq/conf.d/20-definitions.conf:ro,z
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "check_running"]
      interval: 5s
      timeout: 10s
      retries: 24
      start_period: 10s
    networks:
      - messaging

  inventory-db:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: inventory
      POSTGRES_USER: inventory
      POSTGRES_PASSWORD: ${INVENTORY_DB_PASSWORD:-inventory-demo}
    ports:
      - "127.0.0.1:${INVENTORY_DB_PORT:-5441}:5432"
    healthcheck:
      # -h 127.0.0.1: durante la inicialización PostgreSQL solo escucha en el socket unix;
      # sin -h, pg_isready respondería "listo" antes de tiempo.
      test: ["CMD", "pg_isready", "-h", "127.0.0.1", "-U", "inventory", "-d", "inventory"]
      interval: 3s
      timeout: 5s
      retries: 20
    networks:
      - inventory-net

  catalog-db:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: catalog
      POSTGRES_USER: catalog
      POSTGRES_PASSWORD: ${CATALOG_DB_PASSWORD:-catalog-demo}
    ports:
      - "127.0.0.1:${CATALOG_DB_PORT:-5442}:5432"
    healthcheck:
      test: ["CMD", "pg_isready", "-h", "127.0.0.1", "-U", "catalog", "-d", "catalog"]
      interval: 3s
      timeout: 5s
      retries: 20
    networks:
      - catalog-net

networks:
  messaging:
  inventory-net:
  catalog-net:
```

- [ ] **Step 4: Escribir `.env.example`**

`.env.example`:

```bash
# Copia este archivo a .env para cambiar los valores por defecto (todo es opcional).
# Son credenciales de DEMO: no aptas para producción.
# Las credenciales de RabbitMQ (retail / retail-demo) NO se configuran aquí: están en
# infra/rabbitmq/definitions.json como hash.

# Contraseñas de las bases de datos
INVENTORY_DB_PASSWORD=inventory-demo
CATALOG_DB_PASSWORD=catalog-demo

# Puertos publicados en 127.0.0.1 (cámbialos si ya tienes algo escuchando en ellos)
RABBITMQ_AMQP_PORT=5672
RABBITMQ_MGMT_PORT=15672
INVENTORY_DB_PORT=5441
CATALOG_DB_PORT=5442
```

- [ ] **Step 5: Ejecutar el script y verificar que pasa**

Run: `scripts/verify-infra.sh; echo "exit=$?"`
Expected: 7 líneas `PASS` (`docker compose config es válido`, `.env.example coincide con los valores por defecto del Compose`, `los tres servicios llegan a healthy` y los cuatro puertos `publicado solo en 127.0.0.1`) y `exit=0`. La primera ejecución descarga las imágenes.
Run: `docker ps -a --filter name=retail-verify --format '{{.Names}}' | wc -l`
Expected: `0`.

- [ ] **Step 6: Dejar los archivos en el índice**

```bash
git add scripts/verify-infra.sh docker-compose.yml .env.example
```

- [ ] **Step 7: Mutación: un puerto abierto a todas las interfaces debe hacer fallar el script**

Run:

```bash
sed -i 's#"127.0.0.1:${RABBITMQ_AMQP_PORT:-5672}:5672"#"${RABBITMQ_AMQP_PORT:-5672}:5672"#' docker-compose.yml
scripts/verify-infra.sh | grep '^FAIL'; echo "exit=${PIPESTATUS[0]}"
docker ps -a --filter name=retail-verify --format '{{.Names}}' | wc -l
git checkout -- docker-compose.yml
```

Expected: `FAIL: puerto 5672 de rabbitmq publicado solo en 127.0.0.1 (obtenido: 0.0.0.0, esperado: 127.0.0.1)`, `exit=1` y `0` contenedores residuales (la limpieza corre también cuando algo falla).

- [ ] **Step 8: Mutación: un valor de `.env.example` distinto del valor por defecto debe hacer fallar el script**

Run:

```bash
sed -i 's/^INVENTORY_DB_PORT=5441/INVENTORY_DB_PORT=5999/' .env.example
scripts/verify-infra.sh | grep '^FAIL'; echo "exit=${PIPESTATUS[0]}"
git checkout -- .env.example
```

Expected: `FAIL: .env.example difiere de los valores por defecto del Compose` y `exit=1`.

- [ ] **Step 9: Ejecutar de nuevo el script con los archivos restaurados**

Run: `scripts/verify-infra.sh; echo "exit=$?"`
Expected: 7 `PASS` y `exit=0`.

- [ ] **Step 10: Commit**

```bash
git add scripts/verify-infra.sh docker-compose.yml .env.example
git commit -m "feat: add Docker Compose infrastructure and startup verification"
```

---

### Task 2: Verificación de credenciales, aislamiento de redes y topología

**Files:**
- Modify: `scripts/verify-infra.sh` (se agrega un bloque justo antes de la última línea, `exit $status`)

**Interfaces:**
- Consumes: `check` y las variables exportadas de la Tarea 1; `docker-compose.yml` de la Tarea 1.
- Produces: funciones `rabbit_api`, `pg_select_1` y `resolves`, y las variables `PG_IMAGE`, `RABBIT_API`, `RABBIT_AUTH` y `QUEUE`, que reutiliza la Tarea 3; secciones 3, 4 y 5.

- [ ] **Step 1: Agregar el bloque de las secciones 3 a 5**

Insertar el siguiente bloque en `scripts/verify-infra.sh` **inmediatamente antes de la última línea (`exit $status`)**, dejando una línea en blanco antes y después:

```bash
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
check "inventory acepta su usuario"        "$(pg_select_1 retail-verify_inventory-net inventory-db inventory inventory-demo inventory)" "1"
check "catalog acepta su usuario"          "$(pg_select_1 retail-verify_catalog-net catalog-db catalog catalog-demo catalog)" "1"
check "inventory rechaza una contraseña incorrecta" "$(pg_select_1 retail-verify_inventory-net inventory-db inventory incorrecta inventory)" "error"
check "inventory rechaza al usuario de catalog" "$(pg_select_1 retail-verify_inventory-net inventory-db catalog catalog-demo inventory)" "error"
check "catalog rechaza al usuario de inventory" "$(pg_select_1 retail-verify_catalog-net catalog-db inventory inventory-demo catalog)" "error"

echo "== 4. Aislamiento de redes =="
check "catalog-net resuelve catalog-db"      "$(resolves retail-verify_catalog-net catalog-db)" "resolves"
check "catalog-net NO resuelve inventory-db" "$(resolves retail-verify_catalog-net inventory-db)" "unresolved"
check "inventory-net resuelve inventory-db"  "$(resolves retail-verify_inventory-net inventory-db)" "resolves"
check "inventory-net NO resuelve catalog-db" "$(resolves retail-verify_inventory-net catalog-db)" "unresolved"
check "messaging resuelve rabbitmq"          "$(resolves retail-verify_messaging rabbitmq)" "resolves"
check "messaging NO resuelve inventory-db"   "$(resolves retail-verify_messaging inventory-db)" "unresolved"
check "messaging NO resuelve catalog-db"     "$(resolves retail-verify_messaging catalog-db)" "unresolved"

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
```

- [ ] **Step 2: Ejecutar el script y verificar que pasa**

Run: `bash -n scripts/verify-infra.sh && scripts/verify-infra.sh; echo "exit=$?"`
Expected: 24 líneas `PASS` (las 7 de la Tarea 1 más 5 de la sección 3, 7 de la sección 4 y 5 de la sección 5) y `exit=0`.

- [ ] **Step 3: Dejar el script en el índice**

```bash
git add scripts/verify-infra.sh
```

- [ ] **Step 4: Mutación: una red compartida debe hacer fallar el aislamiento**

Run:

```bash
sed -i 's#      - catalog-net#      - catalog-net\n      - inventory-net#' docker-compose.yml
scripts/verify-infra.sh | grep '^FAIL'; echo "exit=${PIPESTATUS[0]}"
docker ps -a --filter name=retail-verify --format '{{.Names}}' | wc -l
git checkout -- docker-compose.yml
```

Expected: `FAIL: inventory-net NO resuelve catalog-db (obtenido: resolves, esperado: unresolved)`, `exit=1` y `0` contenedores residuales.

- [ ] **Step 5: Mutación: RabbitMQ sin sus montajes arranca *healthy* pero sin topología**

Run:

```bash
sed -i -e '/infra\/rabbitmq\//d' -e '/La opción z/d' -e '/^    volumes:$/d' docker-compose.yml
docker compose config -q && echo "compose mutado válido"
scripts/verify-infra.sh | grep -E '^(FAIL|PASS: los tres)'; echo "exit=${PIPESTATUS[0]}"
docker ps -a --filter name=retail-verify --format '{{.Names}}' | wc -l
git checkout -- docker-compose.yml
```

Expected: `compose mutado válido`; `PASS: los tres servicios llegan a healthy` (el healthcheck solo ve que el nodo corre); 5 líneas `FAIL`: `la consola de RabbitMQ responde (obtenido: 401, esperado: 200)`, y las de `exchange inventory.events`, `exchange catalog.dlx`, `cola catalog.inventory-stock-changed` y `cola catalog.inventory-stock-changed.dlq` con `obtenido: null`; `exit=1` y `0` contenedores residuales. Esto demuestra por qué el check 5 es necesario además del healthcheck.

- [ ] **Step 6: Ejecutar de nuevo el script con el Compose restaurado**

Run: `git diff --stat docker-compose.yml; scripts/verify-infra.sh; echo "exit=$?"`
Expected: `git diff` vacío, 24 `PASS` y `exit=0`.

- [ ] **Step 7: Commit**

```bash
git add scripts/verify-infra.sh
git commit -m "test: verify credentials, network isolation and topology in Compose"
```

---

### Task 3: Verificación de persistencia y reinicio, y coexistencia con el stack de desarrollo

**Files:**
- Modify: `scripts/verify-infra.sh` (se agrega un bloque justo antes de la última línea, `exit $status`)

**Interfaces:**
- Consumes: `check`, `rabbit_api`, `QUEUE`, `RABBIT_API` y las variables exportadas de las tareas 1 y 2.
- Produces: funciones `queue_messages`, `wait_for_value` e `inventory_sql`; secciones 6, 7 y 8. El script completo queda con 31 comprobaciones `PASS`.

- [ ] **Step 1: Agregar el bloque de las secciones 6 a 8**

Insertar el siguiente bloque en `scripts/verify-infra.sh` **inmediatamente antes de la última línea (`exit $status`)**, dejando una línea en blanco antes y después:

```bash
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
docker compose down >/dev/null 2>&1
docker compose up -d --wait --wait-timeout 180 >/dev/null 2>&1
check "la tabla de prueba ya no existe" "$(inventory_sql -c "select coalesce(to_regclass('verify_probe')::text, 'none')" 2>/dev/null)" "none"
check "la cola quedó vacía" "$(wait_for_value 20 0 queue_messages)" "0"
```

- [ ] **Step 2: Ejecutar el script completo y verificar que pasa**

Run: `bash -n scripts/verify-infra.sh && scripts/verify-infra.sh; echo "exit=$?"`
Expected: 31 líneas `PASS` (las 24 anteriores más 3 de la sección 6, 2 de la sección 7 y 2 de la sección 8), ninguna `FAIL` y `exit=0`. Tarda alrededor de un minuto y medio.

- [ ] **Step 3: Dejar el script en el índice**

```bash
git add scripts/verify-infra.sh
```

- [ ] **Step 4: Mutación: datos que no sobreviven a `stop`/`start` deben hacer fallar la sección 7**

Run:

```bash
sed -i '/INVENTORY_DB_PORT:-5441/a\    tmpfs:\n      - /var/lib/postgresql/data' docker-compose.yml
docker compose config -q && echo "compose mutado válido"
scripts/verify-infra.sh | grep '^FAIL'; echo "exit=${PIPESTATUS[0]}"
docker ps -a --filter name=retail-verify --format '{{.Names}}' | wc -l
git checkout -- docker-compose.yml
```

Expected: `compose mutado válido`; una sola línea `FAIL: la fila sigue tras stop/start (obtenido: , esperado: 1)`; `exit=1` y `0` contenedores residuales.

- [ ] **Step 5: Mutación: un volumen con nombre debe hacer fallar el reinicio por `down`**

Run:

```bash
sed -i -e '/INVENTORY_DB_PORT:-5441/a\    volumes:\n      - inventory-data:/var/lib/postgresql/data' -e 's/^networks:$/volumes:\n  inventory-data:\n\nnetworks:/' docker-compose.yml
docker compose config -q && echo "compose mutado válido"
scripts/verify-infra.sh | grep '^FAIL'; echo "exit=${PIPESTATUS[0]}"
docker ps -a --filter name=retail-verify --format '{{.Names}}' | wc -l
docker volume ls --filter name=retail-verify -q | wc -l
git checkout -- docker-compose.yml
```

Expected: `compose mutado válido`; una sola línea `FAIL: la tabla de prueba ya no existe (obtenido: verify_probe, esperado: none)`; `exit=1`, `0` contenedores residuales y `0` volúmenes residuales (la limpieza usa `down -v`).

- [ ] **Step 6: Coexistencia con un stack de desarrollo en marcha y un `.env` propio**

Requiere que los puertos `5672`, `15672`, `5441` y `5442` estén libres en el equipo. Run:

```bash
printf 'INVENTORY_DB_PASSWORD=otra-clave\nCATALOG_DB_PASSWORD=otra-clave-2\n' > .env
docker compose up -d --wait; echo "stack de desarrollo: exit=$?"
scripts/verify-infra.sh | grep -E '^(FAIL|PASS: los tres)'; echo "verify exit=${PIPESTATUS[0]}"
docker compose ps --format '{{.Service}}:{{.Health}}'
docker run --rm --network retail_inventory-net -e PGPASSWORD=otra-clave postgres:17-alpine psql -h inventory-db -U inventory -d inventory -tAc 'select 1'
docker compose down -v; rm -f .env
docker ps -a --filter name=retail --format '{{.Names}}' | wc -l
```

Expected: `stack de desarrollo: exit=0`; `PASS: los tres servicios llegan a healthy` sin ninguna `FAIL` y `verify exit=0` (el `.env` con otras contraseñas no rompe la verificación); el stack de desarrollo sigue con `catalog-db:healthy`, `inventory-db:healthy` y `rabbitmq:healthy`; `1` (la base de desarrollo sigue aceptando la clave de su `.env`); `0` contenedores al final. `.env` está en el `.gitignore`.

- [ ] **Step 7: Ejecutar de nuevo el script con el Compose restaurado**

Run: `git diff --stat docker-compose.yml; scripts/verify-infra.sh | grep -c '^PASS'; echo "exit=${PIPESTATUS[0]}"`
Expected: `git diff` vacío, `31` y `exit=0`.

- [ ] **Step 8: Commit**

```bash
git add scripts/verify-infra.sh
git commit -m "test: verify persistence, reset and coexistence with the dev stack"
```

---

### Task 4: Documentación, datos semilla y correcciones a la spec global

**Files:**
- Create: `docs/seed-data.md`
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-10-09-solucion-global-design.md` (§3.5 y §3.8)

**Interfaces:**
- Consumes: `docker-compose.yml`, `.env.example` y `scripts/verify-infra.sh` de las tareas 1 a 3.
- Produces: `docs/seed-data.md`, la referencia de SKU que implementan las fases 4 y 5 y que usan los scripts E2E.

- [ ] **Step 1: Escribir los datos semilla**

`docs/seed-data.md`:

````markdown
# Datos semilla

Conjunto de productos que siembran los dos servicios al arrancar (migración Flyway) y que usan los
scripts E2E. La infraestructura entrega bases vacías; cada servicio siembra la suya con **el mismo
conjunto de SKU**. Diseño en la
[spec de infraestructura](superpowers/specs/2026-10-10-infraestructura-design.md).

| SKU | Producto | Precio (COP) | Stock inicial | Escenario que lo usa |
|---|---|---|---|---|
| `TENIS-RUNNER-42` | Tenis Runner talla 42 | 289 900 | 5 | Flujo normal (POS y online) |
| `BALON-FUTBOL-5` | Balón de fútbol n.º 5 | 119 900 | 1 | Última unidad (carrera POS vs. online) |
| `CAMISETA-DRYFIT-M` | Camiseta Dry-Fit talla M | 79 900 | 0 | Venta sin stock |
| `MOCHILA-TRAIL-20L` | Mochila Trail 20 L | 189 900 | 10 | Catálogo caído (varias ventas acumuladas) |
| `BOTELLA-TERMICA-750` | Botella térmica 750 ml | 59 900 | 10 | RabbitMQ caído (outbox) |
| `GORRA-SPORT-NEGRA` | Gorra Sport negra | 39 900 | 50 | Medición de latencia (20 ventas, con margen) |

## Reglas

- Cada escenario usa **su propio SKU**, de modo que no se interfieren y pueden ensayarse en
  cualquier orden. Para volver al estado inicial basta `docker compose down` y `docker compose up -d --wait`.
- **Inventario** siembra `version = 1` con el stock de la tabla.
- **Catálogo** siembra la misma disponibilidad, su versión interna del stock en `0` (de modo que
  cualquier primer evento, con `stockVersion >= 1`, se aplique), `updatedAt` igual a la hora del
  sembrado y `lastEventLatencyMs = null`.
- Los SKU cumplen el patrón del contrato (`^[A-Z0-9][A-Z0-9-]*$`). Los valores de los ejemplos de
  los OpenAPI son ilustrativos y no necesitan coincidir con este sembrado.
- `currency` de todos los productos: `COP`.
````

- [ ] **Step 2: Actualizar el README principal**

En `README.md`, reemplazar **todo lo que sigue a la línea `## Estructura`** (el bloque de código del árbol y la línea final sobre los contratos) por:

````markdown
## Estructura
```
docker-compose.yml        Infraestructura: RabbitMQ y una base PostgreSQL por servicio
docs/contracts/           Contratos: OpenAPI, JSON Schema del evento (ver su README)
docs/seed-data.md         Datos semilla compartidos por los servicios y los E2E
docs/superpowers/specs/   Especificaciones de diseño
docs/superpowers/plans/   Planes de implementación
infra/rabbitmq/           Topología y configuración de RabbitMQ
scripts/                  Scripts de verificación y utilidades
```

## Infraestructura

Requiere Docker con Compose.

```bash
docker compose up -d --wait   # levanta RabbitMQ y las dos bases de datos
docker compose down           # detiene todo y borra los datos: reinicia el estado de la demo
```

Para levantar solo la infraestructura (cuando los servicios ya estén en el Compose):
`docker compose up -d --wait rabbitmq inventory-db catalog-db`.

| Componente | Desde el host | Desde otro contenedor | Credenciales de demo |
|---|---|---|---|
| RabbitMQ (AMQP) | `localhost:5672` | `rabbitmq:5672` | `retail` / `retail-demo` |
| Consola de RabbitMQ | http://localhost:15672 | — | `retail` / `retail-demo` |
| Base de Inventario | `localhost:5441` | `inventory-db:5432` | `inventory` / `inventory-demo`, base `inventory` |
| Base de Catálogo | `localhost:5442` | `catalog-db:5432` | `catalog` / `catalog-demo`, base `catalog` |

Son credenciales de **demo, no aptas para producción**. Los puertos se publican solo en
`127.0.0.1`.

Un servicio puede correr dentro de Compose (usa los nombres de la columna "Desde otro
contenedor") o desde el IDE con `./mvnw spring-boot:run`, deteniendo antes su contenedor y usando
los puertos de la columna "Desde el host". Las variables de entorno de cada servicio están en la
[spec de infraestructura](docs/superpowers/specs/2026-10-10-infraestructura-design.md).

Datos semilla: [docs/seed-data.md](docs/seed-data.md).

### Problemas conocidos

- **Puerto ocupado:** copia `.env.example` a `.env` y cambia los puertos y las contraseñas que
  necesites. Las credenciales de RabbitMQ no se cambian ahí: están en
  `infra/rabbitmq/definitions.json` como hash.
- **SELinux (Fedora):** los montajes de RabbitMQ llevan la opción `z`. Sin ella, el broker arranca
  pero no carga la topología.
- **`docker compose down` borra los datos.** Es el reinicio buscado para la demo; `stop` y `start`
  sí los conservan.

## Verificación

Requiere Docker; la verificación de la infraestructura también necesita `curl` y `jq`.

```bash
scripts/verify-contracts.sh   # contratos: OpenAPI, schema del evento y topología de RabbitMQ
scripts/verify-infra.sh       # infraestructura: Compose, credenciales, redes y persistencia (~1,5 min)
```

`verify-infra.sh` usa su propio proyecto (`retail-verify`) y sus propios puertos, de modo que puede
ejecutarse con el stack de desarrollo levantado.
````

- [ ] **Step 3: Agregar los comandos a `CLAUDE.md`**

En `CLAUDE.md`, agregar esta sección inmediatamente **antes** de `## Convenciones`:

```markdown
## Comandos
- `docker compose up -d --wait` levanta RabbitMQ y las bases de datos; `docker compose down` las
  detiene y borra los datos (reinicia el estado de la demo).
- `scripts/verify-contracts.sh` verifica los contratos; `scripts/verify-infra.sh` verifica la
  infraestructura (ambos requieren Docker).
- Datos semilla de referencia: `docs/seed-data.md`.
```

- [ ] **Step 4: Corregir la spec global (§3.5 y §3.8)**

En `docs/superpowers/specs/2026-10-09-solucion-global-design.md`, reemplazar el párrafo de la sección 3.5:

```
Ambos servicios arrancan con los mismos SKU sembrados: Inventario con cantidades y Catálogo con
datos de producto y disponibilidad inicial. Los SKU de la demo tienen nombres legibles (por
ejemplo `TENIS-RUNNER-42` con stock 1 para el escenario de la última unidad).
```

por:

```
Ambos servicios arrancan con los mismos SKU sembrados: Inventario con cantidades y Catálogo con
datos de producto y disponibilidad inicial. El conjunto de SKU, con el escenario que usa cada
uno, está en [`docs/seed-data.md`](../../seed-data.md). Inventario siembra `version = 1` y el
Catálogo su versión interna en `0`.
```

y, en la sección 3.8, reemplazar la frase final `La infraestructura vive en `infra/` y los scripts E2E en `e2e/`.` por:

```
El `docker-compose.yml` vive en la raíz, las configuraciones de la infraestructura en `infra/` y
los scripts E2E en `e2e/`.
```

- [ ] **Step 5: Verificar los enlaces relativos y la coherencia de los SKU**

Run:

```bash
for f in README.md docs/seed-data.md docs/superpowers/specs/2026-10-09-solucion-global-design.md docs/superpowers/specs/2026-10-10-infraestructura-design.md; do d=$(dirname "$f"); grep -o '](\([^)#]*\)' "$f" | sed 's/](//' | grep -v '^http' | while read -r l; do [ -e "$d/$l" ] || echo "ROTO: $f -> $l"; done; done; echo "enlaces revisados"
diff <(grep -oE '^\| `[A-Z0-9][A-Z0-9-]*`' docs/seed-data.md | sort) <(sed -n '/^## 6\. Datos semilla/,/^## 7\./p' docs/superpowers/specs/2026-10-10-infraestructura-design.md | grep -oE '^\| `[A-Z0-9][A-Z0-9-]*`' | sort) && echo "SKU coherentes"
grep -c 'seed-data.md' docs/superpowers/specs/2026-10-09-solucion-global-design.md
grep -c 'docker-compose.yml. vive en la raíz' docs/superpowers/specs/2026-10-09-solucion-global-design.md
```

Expected: ninguna línea `ROTO`, luego `enlaces revisados`, `SKU coherentes`, `1` y `1`.

- [ ] **Step 6: Ejecutar los comandos del README tal como están escritos**

Requiere los puertos por defecto libres. Run:

```bash
docker compose up -d --wait; echo "up exit=$?"
docker compose ps --format '{{.Service}}:{{.Health}}'
curl -s -o /dev/null -w '%{http_code}\n' -u retail:retail-demo http://localhost:15672/api/overview
docker compose down; docker ps -a --filter name=retail --format '{{.Names}}' | wc -l
```

Expected: `up exit=0`; `catalog-db:healthy`, `inventory-db:healthy` y `rabbitmq:healthy`; `200`; `0`.

- [ ] **Step 7: Ejecutar ambas verificaciones**

Run: `scripts/verify-contracts.sh | tail -1; scripts/verify-infra.sh | grep -c '^PASS'`
Expected: `Contratos: OK` y `31`.

- [ ] **Step 8: Commit**

```bash
git add docs/seed-data.md README.md CLAUDE.md docs/superpowers/specs/2026-10-09-solucion-global-design.md
git commit -m "docs: document infrastructure and shared seed data"
```
