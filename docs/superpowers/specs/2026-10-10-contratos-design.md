# Spec — Contratos (APIs REST, evento y topología de mensajería)

- **Fecha:** 2026-10-10
- **Fase:** 2 (contratos)
- **Estado:** pendiente de revisión
- **Depende de:** [Spec global](2026-10-09-solucion-global-design.md)

## 1. Objetivo y alcance

Fijar las interfaces de las que dependen todos los demás componentes: la API REST de
`inventory-service`, la API REST de `catalog-service`, el evento `InventoryStockChanged`, la
topología de RabbitMQ y el esquema de seguridad. Estos contratos son la "propuesta de servicios
REST con todos sus componentes" que pide la entrega final.

Esta fase produce solo artefactos de contrato (YAML y JSON) y su verificación. No incluye código
de los servicios.

## 2. Enfoque: contrato primero

- Los contratos se escriben a mano en `docs/contracts/` y son la fuente de verdad.
- Los controladores se implementan a mano siguiendo el contrato; las pruebas de integración de
  cada servicio verifican rutas, códigos de estado y campos.
- Cada servicio publica su contrato en Swagger UI. Al compilar, Maven copia el YAML desde
  `docs/contracts/openapi/` a los recursos del servicio; no se mantienen copias a mano.
- **Descartado:** generación de código con openapi-generator (configuración costosa y riesgo de
  incompatibilidad con Java 25 y la versión actual de Spring Boot) y enfoque código primero con
  springdoc (contradice el diseño antes del código).

### 2.1 Archivos

```
docs/contracts/
  openapi/
    inventory-service.yaml                 OpenAPI 3.1, autocontenido
    catalog-service.yaml                   OpenAPI 3.1, autocontenido
  events/
    inventory-stock-changed.schema.json    JSON Schema (draft 2020-12)
    examples/valid.json
    examples/invalid-negative-stock.json
    examples/invalid-malformed.txt
infra/rabbitmq/
  definitions.json                         Topología de RabbitMQ cargada al arrancar el broker
```

Cada YAML de OpenAPI incluye su propio esquema de error, sin referencias entre archivos.

## 3. API de inventory-service

- Puerto 8081. Prefijo `/api/v1/inventory`.
- Servicio interno: los canales consultan disponibilidad a través del Catálogo.

### 3.1 `POST /api/v1/inventory/{sku}/sales` — registrar una venta

- **Acceso:** JWT con `inventory:sell`.
- **Request:**

```json
{ "quantity": 1, "channel": "POS" }
```

| Campo | Reglas |
|---|---|
| `quantity` | Entero ≥ 1, obligatorio |
| `channel` | `POS` \| `ONLINE`, obligatorio |

- **Response `201 Created`:**

```json
{
  "saleId": "7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73",
  "sku": "TENIS-RUNNER-42",
  "quantity": 1,
  "channel": "POS",
  "remainingStock": 4,
  "version": 8,
  "occurredAt": "2026-10-10T15:04:05.123Z"
}
```

- `saleId` es el mismo UUID que el `eventId` del evento generado, lo que permite seguir una
  venta desde la respuesta hasta el Catálogo.
- `version` es la versión del stock tras la venta (igual al `stockVersion` del evento).
- No existe una tabla de ventas (corresponde al Servicio de Ventas, fuera de alcance), por lo
  que la respuesta no incluye encabezado `Location`.

| Status | Problema | Cuándo |
|---|---|---|
| `201` | — | Venta confirmada |
| `400` | `validation-error` | Cuerpo inválido (cantidad ≤ 0, canal desconocido, JSON mal formado) |
| `401` | `unauthorized` | Sin token o token inválido/expirado |
| `403` | `forbidden` | Token sin `inventory:sell` |
| `404` | `not-found` | SKU inexistente |
| `409` | `insufficient-stock` | El stock no alcanza |
| `409` | `concurrent-modification` | Se agotaron los reintentos por bloqueo optimista |

### 3.2 `GET /api/v1/inventory/{sku}` — stock actual

- **Acceso:** JWT con `inventory:read`.
- **Response `200`:**

```json
{ "sku": "TENIS-RUNNER-42", "stock": 4, "version": 8, "updatedAt": "2026-10-10T15:04:05.123Z" }
```

- Errores: `401`, `403`, `404`.

### 3.3 `GET /api/v1/inventory/outbox?status=PENDING|PUBLISHED` — diagnóstico del outbox

- **Acceso:** público. Se marca en el contrato como *endpoint de diagnóstico para la demo*; en
  un entorno real estaría protegido o no existiría.
- `status` es opcional; sin él se devuelven ambos estados.
- Devuelve como máximo 50 eventos, los más recientes primero.
- **Response `200`:**

```json
[
  {
    "eventId": "7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73",
    "sku": "TENIS-RUNNER-42",
    "status": "PENDING",
    "attempts": 3,
    "lastError": "Connection refused",
    "occurredAt": "2026-10-10T15:04:05.123Z",
    "publishedAt": null
  }
]
```

- Errores: `400` si `status` tiene un valor desconocido.

### 3.4 Fuera del contrato

Crear o editar productos y reponer stock. Los datos se siembran al arrancar el servicio.

## 4. API de catalog-service

- Puerto 8082. Prefijo `/api/v1/catalog`. Todas las rutas son públicas y de solo lectura.

### 4.1 `GET /api/v1/catalog/products` — lista de productos

- Sin paginación ni filtros. Se siembran entre 5 y 8 productos.
- **Response `200`:** arreglo de `Product`.

### 4.2 `GET /api/v1/catalog/products/{sku}` — un producto

- **Response `200`:** un `Product`. **Errores:** `404 not-found`.

### 4.3 Objeto `Product`

```json
{
  "sku": "TENIS-RUNNER-42",
  "name": "Tenis Runner talla 42",
  "description": "Tenis para correr, malla transpirable",
  "price": 289900,
  "currency": "COP",
  "availableStock": 4,
  "inStock": true,
  "updatedAt": "2026-10-10T15:04:05.512Z",
  "lastEventLatencyMs": 389
}
```

| Campo | Reglas |
|---|---|
| `price` | Entero en pesos colombianos (sin decimales) |
| `currency` | Constante `COP` |
| `inStock` | `availableStock > 0` |
| `updatedAt` | Momento en que el Catálogo aplicó el último cambio (o el sembrado inicial) |
| `lastEventLatencyMs` | `updatedAt − occurredAt` del último evento aplicado; `null` si el producto no ha recibido eventos |

No se expone la versión interna del stock ni ningún endpoint de escritura: la disponibilidad
solo cambia por eventos.

## 5. Evento `InventoryStockChanged`

### 5.1 Cuerpo (JSON)

```json
{
  "eventId": "7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73",
  "eventType": "InventoryStockChanged",
  "sku": "TENIS-RUNNER-42",
  "newStock": 4,
  "stockVersion": 8,
  "channel": "POS",
  "occurredAt": "2026-10-10T15:04:05.123Z"
}
```

| Campo | Tipo y reglas | Uso |
|---|---|---|
| `eventId` | UUID, obligatorio | Idempotencia; igual al `saleId` |
| `eventType` | Constante `"InventoryStockChanged"` | Identificación en logs y consola |
| `sku` | String no vacío, obligatorio | Producto afectado |
| `newStock` | Entero ≥ 0, obligatorio | Stock absoluto resultante |
| `stockVersion` | Entero ≥ 1, obligatorio | Orden de los cambios |
| `channel` | `POS` \| `ONLINE`, obligatorio | Trazabilidad |
| `occurredAt` | Fecha-hora ISO-8601 en UTC con milisegundos, obligatorio | Base de la métrica de latencia |

### 5.2 Propiedades AMQP

| Propiedad | Valor |
|---|---|
| `message_id` | `eventId` |
| `type` | `InventoryStockChanged` |
| `content_type` | `application/json` |
| `delivery_mode` | `2` (persistente) |

### 5.3 Compatibilidad

- **Productor estricto:** lo publicado cumple el JSON Schema sin campos extra
  (`additionalProperties: false`).
- **Consumidor tolerante (*Tolerant Reader*):** el Catálogo ignora campos desconocidos, de modo
  que agregar campos no lo rompe.
- **Cambios incompatibles** (quitar o renombrar campos, cambiar tipos): se publicaría un evento
  nuevo, por ejemplo `InventoryStockChangedV2`. Queda documentado como política; no se implementa.

### 5.4 Mensaje inválido

Se considera inválido (fallo permanente, va a la DLQ) un mensaje con: JSON mal formado, un campo
obligatorio ausente, `newStock` negativo, `stockVersion` < 1, un canal desconocido o un SKU que
el Catálogo no tiene.

## 6. Topología de RabbitMQ

```
inventory-service ──publish──► [inventory.events]  (topic)
                               routing key: inventory.stock.changed
                                      │
                                      ▼  binding: inventory.stock.changed
                     [catalog.inventory-stock-changed]  (cola durable)
                                      │  nack sin reencolar
                                      ▼
                               [catalog.dlx] (direct) ──► [catalog.inventory-stock-changed.dlq]
```

| Elemento | Nombre | Configuración | Dueño |
|---|---|---|---|
| Exchange de eventos | `inventory.events` | `topic`, durable | Inventario |
| Routing key | `inventory.stock.changed` | — | Inventario |
| Cola del Catálogo | `catalog.inventory-stock-changed` | Durable, clásica, `x-dead-letter-exchange = catalog.dlx`, `x-dead-letter-routing-key = catalog.inventory-stock-changed.dlq` | Catálogo |
| Exchange de mensajes muertos | `catalog.dlx` | `direct`, durable | Catálogo |
| DLQ | `catalog.inventory-stock-changed.dlq` | Durable, enlazada a `catalog.dlx` con la routing key `catalog.inventory-stock-changed.dlq` | Catálogo |

- **`topic`** permite que consumidores futuros se suscriban a patrones (`inventory.stock.*`,
  `inventory.#`) sin modificar Inventario.
- **Cada consumidor es dueño de su cola**, con prefijo propio; un consumidor nuevo crea la suya y
  recibe su propia copia de cada evento.

### 6.1 Declaración de la topología

- La topología completa se declara en `infra/rabbitmq/definitions.json`, que RabbitMQ carga al
  arrancar. La cola existe desde el inicio aunque el Catálogo nunca haya arrancado.
- Los servicios no declaran exchanges ni colas.
- Las pruebas con Testcontainers cargan el mismo archivo.

### 6.2 Publicación

- Publicación con *publisher confirms* y `mandatory = true`. Un mensaje devuelto por no tener
  cola destino se trata como fallo: el evento queda `PENDING` en el outbox. Esto evita que un
  evento sin cola se pierda en silencio.

### 6.3 Consumo

- Un solo consumidor con `prefetch = 1` y ack manual: los mensajes se procesan de uno en uno y
  en orden.
- **Cola clásica y no quorum:** con un único nodo de RabbitMQ una cola quorum no aporta
  replicación. En un clúster de producción se usaría quorum; se menciona en el documento.

## 7. Seguridad

### 7.1 JWT

- Solo `inventory-service` valida tokens; el Catálogo no tiene seguridad.
- HS256 con el secreto de la variable de entorno `JWT_SECRET` (al menos 32 bytes). El
  `docker-compose` incluye un valor de demo, advertido como no apto para producción.
- Validación con el *resource server* nativo de Spring Security, que convierte `scope` en
  autoridades `SCOPE_<permiso>`.

| Claim | Valor |
|---|---|
| `iss` | `retail-demo-auth` (se valida) |
| `sub` | Identificador de quien llama |
| `scope` | Permisos separados por espacio |
| `exp` | 24 h desde la emisión |

### 7.2 Perfiles de token

Generados con `scripts/generate-token.sh <perfil>` (bash + openssl):

| Perfil | `sub` | `scope` | Uso |
|---|---|---|---|
| `pos` | `pos-store-001` | `inventory:sell` | Ventas en POS |
| `online` | `online-store` | `inventory:sell` | Ventas online |
| `operator` | `operator` | `inventory:read` | Consultar el stock de Inventario |
| `no-scope` | `test-client` | (vacío) | Probar el `403` |

### 7.3 Matriz de acceso

| Endpoint | Acceso |
|---|---|
| `POST /api/v1/inventory/{sku}/sales` | `inventory:sell` |
| `GET /api/v1/inventory/{sku}` | `inventory:read` |
| `GET /api/v1/inventory/outbox` | Público (diagnóstico de demo) |
| Swagger UI y `/actuator/health` de ambos servicios | Público |
| Todos los endpoints del Catálogo | Público |

## 8. Errores (RFC 9457)

Formato `application/problem+json` con los campos `type`, `title`, `status`, `detail` e
`instance`. El `type` usa la forma `https://api.retail-demo.local/problems/<código>`; es un
dominio ficticio, lo cual el RFC permite.

| Código | Status | Servicio | Campos extra |
|---|---|---|---|
| `validation-error` | 400 | Inventario | `errors: [{ "field", "message" }]` |
| `unauthorized` | 401 | Inventario | — |
| `forbidden` | 403 | Inventario | — |
| `not-found` | 404 | Ambos | — |
| `insufficient-stock` | 409 | Inventario | `availableStock`, `requestedQuantity` |
| `concurrent-modification` | 409 | Inventario | — |
| `internal-error` | 500 | Ambos | — (sin detalles internos) |

Ejemplo:

```json
{
  "type": "https://api.retail-demo.local/problems/insufficient-stock",
  "title": "Insufficient stock",
  "status": 409,
  "detail": "SKU TENIS-RUNNER-42 has 0 units available, 1 requested",
  "instance": "/api/v1/inventory/TENIS-RUNNER-42/sales",
  "availableStock": 0,
  "requestedQuantity": 1
}
```

## 9. Verificación de los contratos (en esta fase)

Sin instalar herramientas en el equipo; todo se ejecuta con contenedores Docker:

- **OpenAPI:** ambos YAML pasan el lint de Redocly CLI sin errores.
- **JSON Schema:** `examples/valid.json` valida contra el schema; cada ejemplo inválido falla.
- **Topología:** un RabbitMQ en Docker arranca cargando `definitions.json` y la API de
  administración muestra los exchanges, colas y bindings de la sección 6.

La verificación de que el código cumple los contratos ocurre en las fases 4 y 5 (pruebas de
integración y de contrato del evento).

## 10. Impacto en la spec global

- El campo de versión del evento se llama `stockVersion` (ya actualizado en la spec global).
- La topología se declara en el broker mediante `definitions.json` y no en los servicios.
