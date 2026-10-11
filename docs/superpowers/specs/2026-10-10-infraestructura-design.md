# Spec — Infraestructura (Docker Compose)

- **Fecha:** 2026-10-10
- **Fase:** 3 (orquestación e infraestructura)
- **Estado:** pendiente de revisión
- **Depende de:** [Spec global](2026-10-09-solucion-global-design.md) y [Spec de contratos](2026-10-10-contratos-design.md)

## 1. Objetivo y alcance

Dejar lista la infraestructura sobre la que se construyen y prueban los servicios: RabbitMQ con la
topología ya definida y una base PostgreSQL independiente para cada servicio, orquestadas con
Docker Compose.

Al terminar esta fase, `docker compose up -d --wait` levanta RabbitMQ con la topología cargada y
dos bases PostgreSQL vacías y aisladas, y `scripts/verify-infra.sh` lo comprueba de punta a punta.

**Incluye:** `docker-compose.yml`, `.env.example`, la definición de las variables de entorno que
usarán los servicios, el conjunto de datos semilla, el script de verificación y la documentación.

**No incluye:** el código ni los `Dockerfile` de los servicios (fases 4 y 5), y la verificación de
que los servicios se conectan con estas variables (pruebas de integración de las fases 4 y 5 y
E2E de la fase 6).

### Fuera de alcance (YAGNI)

Volúmenes con nombre, política de reinicio (`restart`), pgAdmin u otro cliente web de bases de
datos, perfiles de Compose (`profiles`), TLS en RabbitMQ y un `Makefile`.

## 2. Estructura

Un solo `docker-compose.yml` **en la raíz del repositorio**. Esta fase define la infraestructura;
las fases 4 y 5 agregan cada servicio al mismo archivo, con su propio `Dockerfile` y
`build: ./inventory-service` / `build: ./catalog-service`. En `infra/` quedan solo las
configuraciones (`infra/rabbitmq/`, que ya existe).

- **Por qué en la raíz:** los contextos de construcción de los servicios viven en la raíz y las
  rutas quedan simples (sin `../`); además todo se levanta con un solo comando desde la raíz.
- **Por qué un solo archivo:** los escenarios E2E detienen servicios con
  `docker compose stop <servicio>`, de modo que los servicios deben terminar dentro de Compose.
  Desde ese mismo archivo se puede levantar solo la infraestructura
  (`docker compose up -d --wait rabbitmq inventory-db catalog-db`).
- **Descartado:** dos archivos (infraestructura y aplicación) por comandos más largos sin
  beneficio claro, y servicios siempre desde el host porque apagar un proceso del host no se ve
  igual de limpio en la demo que `docker compose stop`.

## 3. Servicios

| Servicio | Imagen | Puertos en el host (solo `127.0.0.1`) | Base y credenciales por defecto |
|---|---|---|---|
| `rabbitmq` | `rabbitmq:4.3-management` | `5672` (AMQP), `15672` (consola) | usuario `retail` / `retail-demo`; topología desde `definitions.json` |
| `inventory-db` | PostgreSQL, versión fija | `5441` → 5432 | base `inventory`, usuario `inventory` |
| `catalog-db` | PostgreSQL, versión fija | `5442` → 5432 | base `catalog`, usuario `catalog` |

- Las versiones exactas de las imágenes se confirman al escribir el plan, comprobando que la
  etiqueta existe, y quedan fijas (sin `latest`).
- Los puertos del host se publican solo en `127.0.0.1`. `5441` y `5442` evitan chocar con un
  PostgreSQL local en el `5432`.
- **Puertos configurables:** `RABBITMQ_AMQP_PORT` (5672), `RABBITMQ_MGMT_PORT` (15672),
  `INVENTORY_DB_PORT` (5441) y `CATALOG_DB_PORT` (5442), con los valores por defecto indicados.
- **RabbitMQ** monta `infra/rabbitmq/definitions.json` en `/etc/rabbitmq/definitions.json` y
  `infra/rabbitmq/rabbitmq.conf` en `/etc/rabbitmq/conf.d/20-definitions.conf`, ambos con
  `ro,z` (la opción `z` es necesaria en Fedora por SELinux).
- **Contraseñas de las bases** por variables con valor por defecto
  (`${INVENTORY_DB_PASSWORD:-inventory-demo}`, `${CATALOG_DB_PASSWORD:-catalog-demo}`), de modo
  que funciona sin crear un `.env`. Son credenciales de demo y se advierte en el README.
- **Credenciales de RabbitMQ no parametrizables en el Compose:** el usuario y su contraseña están
  en `definitions.json` (como hash), así que una variable de entorno no cambiaría nada en el
  broker. Cambiarlas exige regenerar el hash en ese archivo.
- **Healthchecks:** `pg_isready -U <usuario> -d <base>` en las bases y
  `rabbitmq-diagnostics -q check_running` en RabbitMQ. `--wait` espera a que los tres estén sanos.
  Las fases 4 y 5 agregarán `depends_on` con `condition: service_healthy`.
- **Sin volúmenes con nombre:** los datos sobreviven a `docker compose stop` y `start` (lo que
  necesitan los escenarios 5 y 8) y `docker compose down` deja todo limpio, que es el reinicio de
  la demo. `down` borra los datos, y para una demo es el comportamiento buscado.
- **Sin política `restart`:** un contenedor detenido a propósito en la demo debe quedarse detenido.

## 4. Redes y aislamiento

| Red | Miembros |
|---|---|
| `messaging` | `rabbitmq` y, en las fases 4 y 5, `inventory-service` y `catalog-service` |
| `inventory-net` | `inventory-db` y, en la fase 4, `inventory-service` |
| `catalog-net` | `catalog-db` y, en la fase 5, `catalog-service` |

El Catálogo no puede ni resolver el nombre `inventory-db`. Así la regla "el Catálogo nunca lee la
base de datos de Inventario" se hace cumplir en la infraestructura, no solo por convención.

## 5. Variables de entorno de los servicios

Esta es la interfaz que implementan las fases 4 y 5. Usan los nombres que Spring Boot enlaza solo
(*relaxed binding*), sin código de mapeo.

| Variable | Inventario | Catálogo |
|---|---|---|
| `SERVER_PORT` | `8081` | `8082` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://inventory-db:5432/inventory` | `jdbc:postgresql://catalog-db:5432/catalog` |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `inventory` / `inventory-demo` | `catalog` / `catalog-demo` |
| `SPRING_RABBITMQ_HOST` / `SPRING_RABBITMQ_PORT` | `rabbitmq` / `5672` | `rabbitmq` / `5672` |
| `SPRING_RABBITMQ_USERNAME` / `SPRING_RABBITMQ_PASSWORD` | `retail` / `retail-demo` | `retail` / `retail-demo` |
| `JWT_SECRET` | valor de demo (al menos 32 bytes) | no aplica |

Dos modos de ejecución de un servicio:

- **Dentro de Compose:** los hosts son los nombres de servicio de la tabla anterior.
- **Desde el IDE o con `./mvnw spring-boot:run`:** se detiene el contenedor del servicio y se usan
  `localhost:5441` / `localhost:5442` para la base y `localhost:5672` para RabbitMQ.

Las pruebas de integración con Testcontainers no usan este Compose; cargan
`infra/rabbitmq/definitions.json` y deben configurar el contenedor con
`.withAdminUser("retail").withAdminPassword("retail-demo")` (ver el README de contratos).

## 6. Datos semilla

La infraestructura entrega bases **vacías**. Cada servicio siembra la suya al arrancar (migración
Flyway) con el **mismo conjunto de SKU**. La referencia compartida vive en
[`docs/seed-data.md`](../../seed-data.md) y es la fuente para las fases 4 y 5 y para los scripts
E2E.

| SKU | Producto | Precio (COP) | Stock inicial | Escenario que lo usa |
|---|---|---|---|---|
| `TENIS-RUNNER-42` | Tenis Runner talla 42 | 289 900 | 5 | Flujo normal (POS y online) |
| `BALON-FUTBOL-5` | Balón de fútbol n.º 5 | 119 900 | 1 | Última unidad (carrera POS vs. online) |
| `CAMISETA-DRYFIT-M` | Camiseta Dry-Fit talla M | 79 900 | 0 | Venta sin stock |
| `MOCHILA-TRAIL-20L` | Mochila Trail 20 L | 189 900 | 10 | Catálogo caído (varias ventas acumuladas) |
| `BOTELLA-TERMICA-750` | Botella térmica 750 ml | 59 900 | 10 | RabbitMQ caído (outbox) |
| `GORRA-SPORT-NEGRA` | Gorra Sport negra | 39 900 | 50 | Medición de latencia (20 ventas, con margen) |

- Cada escenario usa **su propio SKU**, así que no se interfieren y pueden ensayarse en cualquier
  orden. Para volver al estado inicial basta `docker compose down` y `up`.
- **Inventario** siembra `version = 1` con el stock de la tabla.
- **Catálogo** siembra la misma disponibilidad, su versión interna en `0` (de modo que cualquier
  primer evento, con `stockVersion >= 1`, se aplique), `updatedAt` igual a la hora del sembrado y
  `lastEventLatencyMs = null`.
- Los SKU cumplen el patrón del contrato (`^[A-Z0-9][A-Z0-9-]*$`). Los valores de los ejemplos de
  los OpenAPI son ilustrativos y no necesitan coincidir con el sembrado.

## 7. Verificación

Un script, `scripts/verify-infra.sh`, que se ejecuta solo con Docker (sin instalar nada). Se
escribe primero, se ve fallar y después se crea el Compose hasta que pasa.

- Usa un **nombre de proyecto propio** (`retail-verify`) y **puertos propios** mediante las
  variables de la sección 3, de modo que no toca ni choca con un stack de desarrollo en marcha.
- Limpia con `down -v` de su propio proyecto al terminar, también si algo falla (`trap`).
- Tarda un par de minutos, sobre todo por los arranques de RabbitMQ.

| # | Comprobación | Qué demuestra |
|---|---|---|
| 1 | `docker compose config -q` | El archivo es válido |
| 2 | `docker compose up -d --wait` termina bien | Los tres servicios llegan a *healthy* |
| 3 | Cada base acepta su propio usuario (`select 1`) y rechaza el del otro | Credenciales y bases separadas |
| 4 | Desde una red temporal equivalente a `catalog-net`, `catalog-db` resuelve e `inventory-db` no | El aislamiento de redes funciona |
| 5 | La consola de RabbitMQ responde y la API lista los exchanges y colas de la topología | `definitions.json` se cargó dentro del Compose |
| 6 | Se publica un mensaje persistente, `stop` y `start` de `rabbitmq`, y el mensaje sigue en la cola | Lo que necesita el escenario 8 |
| 7 | Se inserta una fila, `stop` y `start` de la base, y la fila sigue | Los datos sobreviven sin volúmenes con nombre |
| 8 | `docker compose down` y `up --wait`, y la fila ya no está | `down` es el reinicio de la demo |

La semántica de la topología (enrutamiento, dead-lettering) ya se verifica en
`scripts/contracts/verify-rabbitmq-topology.sh`; aquí solo se comprueba que el broker del Compose
cargó la misma topología.

**Lo que esta fase no prueba:** que Inventario y Catálogo se conecten con las variables de la
sección 5, porque los servicios aún no existen.

## 8. Documentación

- **README principal:** sección "Infraestructura" con cómo levantarla
  (`docker compose up -d --wait`), cómo levantar solo la infraestructura, cómo reiniciar el estado
  de la demo (`docker compose down` y `up`), una tabla de puertos, URLs y credenciales de demo
  (consola de RabbitMQ en `http://localhost:15672`, con `retail` / `retail-demo`) con la
  advertencia de que no son aptas para producción, los dos modos de ejecución de un servicio y las
  notas de problemas conocidos (puertos ocupados y la opción `:z` por SELinux). Se corrige la
  línea de `infra/` del árbol de carpetas y se agrega `docker-compose.yml` en la raíz.
- **`.env.example`** en la raíz, versionado, con todas las variables opcionales y sus valores por
  defecto. El `.env` real ya está en el `.gitignore`.
- **`docs/seed-data.md`** con la tabla de la sección 6.

## 9. Cambios a las specs anteriores

- **Spec global §3.8:** la infraestructura vive en el `docker-compose.yml` de la raíz; `infra/`
  contiene las configuraciones.
- **Spec global §3.5:** enlaza a `docs/seed-data.md` y fija que Inventario siembra `version = 1` y
  el Catálogo `0`.
- La spec de contratos no cambia.

## 10. Decisiones descartadas

| Alternativa | Motivo |
|---|---|
| `docker-compose.yml` dentro de `infra/` | Rutas de construcción de los servicios con `../` y comandos más largos desde la raíz |
| Dos archivos Compose (infraestructura y aplicación) | Comandos más largos y más archivos, sin beneficio frente a levantar servicios concretos con un solo archivo |
| Servicios siempre desde el host | Detener un proceso del host no se ve igual de limpio en la demo que `docker compose stop` |
| Volúmenes con nombre | Los datos ya sobreviven a `stop`/`start`; `down` es el reinicio buscado |
| Parametrizar las credenciales de RabbitMQ en el Compose | Están en `definitions.json` como hash; la variable no tendría efecto |
| Una sola red | No haría cumplir en la infraestructura que el Catálogo no accede a la base de Inventario |
| pgAdmin y `profiles` | No aportan a ningún escenario (YAGNI) |
