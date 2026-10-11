# Contratos

Fuente de verdad de las interfaces del sistema (enfoque contrato primero). El diseño está en
[la spec de contratos](../superpowers/specs/2026-10-10-contratos-design.md).

| Contrato | Archivo |
|---|---|
| API REST de `inventory-service` | [openapi/inventory-service.yaml](openapi/inventory-service.yaml) |
| API REST de `catalog-service` | [openapi/catalog-service.yaml](openapi/catalog-service.yaml) |
| Evento `InventoryStockChanged` | [events/inventory-stock-changed.schema.json](events/inventory-stock-changed.schema.json) |
| Ejemplos del evento | [events/examples/](events/examples/) |
| Topología de RabbitMQ | [../../infra/rabbitmq/definitions.json](../../infra/rabbitmq/definitions.json) |

## Reglas

- **Productor estricto:** lo que publica `inventory-service` cumple el JSON Schema exactamente
  (sin campos extra y con `occurredAt` en UTC con 3 decimales).
- **Consumidor tolerante:** `catalog-service` ignora campos desconocidos del evento.
- **Cambios incompatibles** en el evento se publican como un evento nuevo
  (por ejemplo, `InventoryStockChangedV2`).
- Un cambio en estos archivos es un cambio de contrato: se discute antes de tocar el código.

## Verificación

Requiere Docker. Desde la raíz del repositorio:

```bash
scripts/verify-contracts.sh
```

Ejecuta el lint de los OpenAPI (Redocly), la validación del schema y sus ejemplos
(check-jsonschema) y una verificación de la topología sobre un RabbitMQ temporal.

## Credenciales de demo (no aptas para producción)

- Usuario `retail`, contraseña `retail-demo`, con el tag `administrator`. Ambos servicios
  comparten este usuario solo para la demo (simplificación conocida).
- No existe el usuario `guest`: RabbitMQ no lo crea cuando `infra/rabbitmq/definitions.json`
  define los usuarios.
- Testcontainers: un `RabbitMQContainer` que cargue `infra/rabbitmq/definitions.json` debe
  configurarse con `.withAdminUser("retail").withAdminPassword("retail-demo")`; con el
  `guest`/`guest` por defecto la autenticación falla.

## Notas de implementación

Puntos a tener en cuenta en las fases 4 y 5 para no divergir del contrato:

- **`occurredAt`:** el schema exige exactamente 3 decimales; `Instant.toString()` imprime 0, 3, 6
  o 9 dígitos (en Java 25 sobre Linux produce microsegundos y omite la fracción si los
  milisegundos son 0). El productor debe truncar a milisegundos y usar un formateador fijo con
  3 decimales en UTC.
- **Versión de sembrado:** Inventario siembra `version = 1` (por eso `StockResponse.version`
  tiene `minimum: 1`); el Catálogo siembra su versión interna del stock en `0` para que
  cualquier primer evento (`stockVersion >= 1`) se aplique.
- **`SaleRequest` estricto** (`additionalProperties: false`, `quantity` entero): Spring Boot por
  defecto ignora propiedades desconocidas y Jackson convierte `1.5` o `"1"` a entero; hay que
  configurar Jackson de forma estricta (por ejemplo `FAIL_ON_UNKNOWN_PROPERTIES` y
  `ALLOW_COERCION_OF_SCALARS` desactivados) o el servicio divergirá del contrato.
- **`ValidationProblem` exige `errors`:** un JSON mal formado también debe responder
  `validation-error` con `errors` (puede ser `[]`).
- **Publisher confirms + `mandatory = true`:** un mensaje devuelto por no tener cola destino
  igualmente recibe ack del broker; hay que revisar `CorrelationData.getReturned()` antes de
  marcar el evento como publicado en el outbox.
- **401/403 en `application/problem+json`:** Spring Security genera esas respuestas antes de
  llegar a los controladores; se requiere un `AuthenticationEntryPoint` y un
  `AccessDeniedHandler` propios que escriban el `ProblemDetail` con `type`
  `https://api.retail-demo.local/problems/unauthorized` o `.../forbidden`.
- **`sku` inválido en la ruta:** un `sku` que no cumple el patrón del path responde
  `404 not-found` (el patrón es informativo en la ruta; no se define un 400).

## Ejemplos del evento

| Archivo | Qué representa |
|---|---|
| `valid.json` | Evento válido |
| `invalid-negative-stock.json` | `newStock` negativo |
| `invalid-missing-field.json` | Falta `sku` |
| `invalid-zero-version.json` | `stockVersion` menor que 1 |
| `invalid-unknown-channel.json` | Canal desconocido (`pos` en minúsculas) |
| `invalid-missing-millis.json` | `occurredAt` sin milisegundos |
| `invalid-extra-field.json` | Campo no definido en el contrato |
| `invalid-malformed.txt` | JSON truncado |
