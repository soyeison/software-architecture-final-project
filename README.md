# Sincronización de inventario orientada a eventos (SOA)

Proyecto final del curso Arquitectura de Software — Politécnico Grancolombiano, Subgrupo 22.

Implementación del patrón **Event-Driven Messaging** para eliminar el desfase entre el stock real
(Servicio de Inventario) y la disponibilidad mostrada en los canales digitales (Servicio de
Catálogo), comunicados mediante RabbitMQ.

## Estructura
```
docker-compose.yml        Infraestructura: RabbitMQ y una base PostgreSQL por servicio
.env.example              Variables opcionales del Compose (copiar a .env)
docs/contracts/           Contratos: OpenAPI, JSON Schema del evento (ver su README)
docs/seed-data.md         Datos semilla compartidos por los servicios y los E2E
docs/superpowers/specs/   Especificaciones de diseño
docs/superpowers/plans/   Planes de implementación
infra/rabbitmq/           Topología y configuración de RabbitMQ
inventory-service/        Servicio de Inventario (Spring Boot)
catalog-service/          Servicio de Catálogo (Spring Boot)
scripts/                  Scripts de verificación y utilidades
```

## Infraestructura

Requiere Docker con Compose.

```bash
docker compose up -d --wait --build   # levanta la infraestructura y los servicios, reconstruyendo sus imágenes (la primera construcción requiere internet y tarda unos minutos)
docker compose down -v        # borra contenedores, redes y volúmenes: reinicia el estado de la demo
```

Para levantar solo la infraestructura:
`docker compose up -d --wait rabbitmq inventory-db catalog-db`.

| Componente | Desde el host | Desde otro contenedor | Credenciales de demo |
|---|---|---|---|
| RabbitMQ (AMQP) | `localhost:5672` | `rabbitmq:5672` | `retail` / `retail-demo` |
| Consola de RabbitMQ | http://localhost:15672 | — | `retail` / `retail-demo` |
| Base de Inventario | `localhost:5441` | `inventory-db:5432` | `inventory` / `inventory-demo`, base `inventory` |
| Base de Catálogo | `localhost:5442` | `catalog-db:5432` | `catalog` / `catalog-demo`, base `catalog` |
| Servicio de Inventario | http://localhost:8081 (Swagger UI en `/swagger-ui/index.html`) | `inventory-service:8081` | JWT de demo: `scripts/generate-token.sh <perfil>` |
| Servicio de Catálogo | http://localhost:8082 (Swagger UI en `/swagger-ui/index.html`) | `catalog-service:8082` | consultas públicas, sin token |

Son credenciales de **demo, no aptas para producción**. Los puertos se publican solo en
`127.0.0.1`.

Un servicio puede correr dentro de Compose (usa los nombres de la columna "Desde otro
contenedor") o desde el IDE (a partir de las fases 4 y 5) con `./mvnw spring-boot:run`, deteniendo antes su contenedor y usando
los puertos de la columna "Desde el host". Las variables de entorno de cada servicio están en la
[spec de infraestructura](docs/superpowers/specs/2026-10-10-infraestructura-design.md).

Datos semilla: [docs/seed-data.md](docs/seed-data.md).

### Problemas conocidos

- **Puerto ocupado:** copia `.env.example` a `.env` y cambia los puertos y las contraseñas que
  necesites. Las credenciales de RabbitMQ no se cambian ahí: están en
  `infra/rabbitmq/definitions.json` como hash.
- **SELinux (Fedora):** los montajes de RabbitMQ llevan la opción `z`. Sin ella, el broker arranca
  pero no carga la topología.
- **`docker compose down -v` borra los datos.** Es el reinicio buscado para la demo; `stop` y
  `start` sí los conservan (el contenedor se mantiene). Un `down` sin `-v` deja volúmenes anónimos
  huérfanos.
- **Cambiar una contraseña de base de datos en `.env`:** PostgreSQL aplica la contraseña solo la
  primera vez que crea la base, así que hay que ejecutar `docker compose down -v` antes del
  siguiente `up`.
- **Un solo stack por equipo:** el proyecto de Compose se llama `retail`, de modo que solo puede
  correr uno a la vez (un segundo checkout o worktree recrearía los contenedores del primero).

## Servicio de Inventario

Código en `inventory-service/` (Java 25, Spring Boot 4, Maven wrapper). Diseño en la
[spec de Inventario](docs/superpowers/specs/2026-10-11-inventario-design.md).

```bash
cd inventory-service
./mvnw test     # pruebas unitarias y de arquitectura (rápidas)
./mvnw verify   # además, las pruebas de integración (*IntegrationTest); requieren Docker (PostgreSQL con Testcontainers)
```

Para correrlo desde el IDE o con `./mvnw spring-boot:run`, detener antes su contenedor
(`docker compose stop inventory-service`) y definir `JWT_SECRET` con al menos 32 bytes, por ejemplo
el valor de `.env.example`. Sin `JWT_SECRET` válido el servicio no arranca, a propósito.

El servicio usa la base de Inventario: al correrlo fuera de Compose, levantar antes `docker compose up -d --wait inventory-db` (puerto 5441). Flyway crea la tabla `stock` y la siembra al arrancar.

Tokens de demo (bash y openssl, sin dependencias): `scripts/generate-token.sh pos` u `online` para
vender, `operator` para consultar el stock y `no-scope` para probar el 403. El script toma
`JWT_SECRET` del entorno, de `.env` o de `.env.example`, así que coincide con el Compose sin
configurar nada.

```bash
TOKEN="$(scripts/generate-token.sh operator)"
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/inventory/TENIS-RUNNER-42
```

Responde `200` con `{"sku":"TENIS-RUNNER-42","stock":5,"version":1,...}`. Un SKU inexistente responde `404 not-found`.

Para registrar una venta (token `pos` u `online`):

```bash
TOKEN="$(scripts/generate-token.sh pos)"
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"quantity": 1, "channel": "POS"}' \
  http://localhost:8081/api/v1/inventory/TENIS-RUNNER-42/sales
```

Responde `201` con el `saleId`, el stock restante y la versión. Sin unidades suficientes responde
`409 insufficient-stock`; si varias ventas del mismo SKU chocan y se agotan los reintentos
(`inventory.sale.max-attempts`, 5 por defecto), `409 concurrent-modification`. El cuerpo se valida de
forma estricta: propiedades desconocidas, cantidades que no son enteros o un canal numérico responden `400 validation-error`.
Cada venta de la semilla es real: para volver al estado inicial, `docker compose down -v`.

Cada venta confirmada escribe, en la misma transacción que descuenta el stock, un evento
`InventoryStockChanged` en la tabla `outbox` con estado `PENDING` (patrón Transactional Outbox). Si el
outbox falla, la venta se revierte entera. Un publicador interno revisa el outbox cada 500 ms y envía los eventos PENDING a RabbitMQ (exchange
`inventory.events`), esperando la confirmación del broker; si RabbitMQ está caído, la venta sigue
funcionando y el evento queda PENDING hasta que vuelva (cero eventos perdidos, en orden por producto). Para verlo
(público, solo para la demo):

```bash
curl -s "http://localhost:8081/api/v1/inventory/outbox?status=PENDING"   # eventos sin publicar
curl -s "http://localhost:8081/api/v1/inventory/outbox"                  # los 50 más recientes
```

Prueba de humo del servicio dentro de Compose (venta → outbox PUBLISHED → mensaje en la cola del
Catálogo; usa su propio proyecto y puertos, requiere Docker, curl y jq):

```bash
scripts/smoke-inventory-service.sh
```

Las rutas no declaradas responden 401 (sin token) o 403 (con token), no 404: el servicio no revela
qué rutas existen. `/v3/api-docs` también se deniega; el contrato publicado es el de Swagger UI.

## Servicio de Catálogo

Código en `catalog-service/` (Java 25, Spring Boot 4, Maven wrapper). Diseño en la
[spec del Catálogo](docs/superpowers/specs/2026-10-12-catalogo-design.md).

```bash
cd catalog-service
./mvnw test     # pruebas unitarias y de arquitectura (rápidas)
./mvnw verify   # además, las pruebas de integración (*IntegrationTest)
```

Es el consumidor del flujo: mantiene la vista de disponibilidad con los eventos de Inventario y la
expone por REST sin token. En esta etapa solo está el esqueleto (arranca, sirve Swagger UI con el
contrato y responde errores en `application/problem+json`); la consulta de productos y el consumidor de
eventos llegan en las siguientes sub-tareas.

## Verificación

Requiere Docker; la verificación de la infraestructura también necesita `curl` y `jq`.

```bash
scripts/verify-contracts.sh   # contratos: OpenAPI, schema del evento y topología de RabbitMQ
scripts/verify-infra.sh       # infraestructura: Compose, credenciales, redes y persistencia (~1,5 min)
scripts/smoke-inventory-service.sh   # servicio de Inventario en Compose: venta, outbox y cola (fase 4)
```

`verify-infra.sh` usa su propio proyecto (`retail-verify`) y sus propios puertos, de modo que puede
ejecutarse con el stack de desarrollo levantado.
