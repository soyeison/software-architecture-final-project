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

Requiere Docker. Desde cualquier directorio del repositorio:

```bash
scripts/verify-contracts.sh
```

Ejecuta el lint de los OpenAPI (Redocly), la validación del schema y sus ejemplos
(check-jsonschema) y una verificación de la topología sobre un RabbitMQ temporal.

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
