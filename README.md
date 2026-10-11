# Sincronización de inventario orientada a eventos (SOA)

Proyecto final del curso Arquitectura de Software — Politécnico Grancolombiano, Subgrupo 22.

Implementación del patrón **Event-Driven Messaging** para eliminar el desfase entre el stock real
(Servicio de Inventario) y la disponibilidad mostrada en los canales digitales (Servicio de
Catálogo), comunicados mediante RabbitMQ.

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
