# Contratos (fase 2) — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Escribir y verificar los contratos del sistema: OpenAPI de `inventory-service` y `catalog-service`, JSON Schema del evento `InventoryStockChanged` con sus ejemplos y la topología de RabbitMQ.

**Architecture:** Contrato primero. Los contratos son archivos escritos a mano en `docs/contracts/` (y la topología en `infra/rabbitmq/`), que son la fuente de verdad para las fases 4 y 5. Cada contrato tiene un script de verificación que se ejecuta con contenedores Docker, sin instalar herramientas en el equipo. El script se escribe primero, se comprueba que falla y luego se escribe el contrato hasta que pasa.

**Tech Stack:** OpenAPI 3.1, JSON Schema draft 2020-12, RabbitMQ 4.3 (definitions.json), Redocly CLI 2.62.1, check-jsonschema 0.38.2, bash, curl, jq, Docker.

**Spec:** `docs/superpowers/specs/2026-10-10-contratos-design.md` (depende de `docs/superpowers/specs/2026-10-09-solucion-global-design.md`).

## Global Constraints

- Código en inglés (rutas, campos, nombres de colas y exchanges, nombres de scripts); documentación, descripciones de los contratos y comentarios en español.
- Commits en inglés, Conventional Commits, breves; se permite `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- La ruta del repositorio contiene espacios ("Arquitectura de Software"): toda ruta en los scripts va entre comillas dobles.
- Los montajes de Docker usan la opción `:z` (Fedora con SELinux bloquea el acceso sin ella con `eacces`; en otros sistemas no tiene efecto).
- Versiones fijas de herramientas: `redocly/cli:2.62.1`, `python:3.13-slim` + `check-jsonschema==0.38.2`, `rabbitmq:4.3-management`.
- Los scripts calculan la raíz con `git rev-parse --show-toplevel`, de modo que funcionan desde cualquier directorio.
- No se escribe código de los servicios en esta fase. El script `scripts/generate-token.sh` (spec §7.2) y la copia del YAML a los servicios (spec §2) pertenecen a la fase 4.

## Review Focus

Modos de fallo que el spec implica y que conviene fijar con pruebas en esta fase:

1. **Un SKU llamado `outbox` choca con la ruta `GET /api/v1/inventory/outbox`.** Se espera que nunca sea un SKU válido. Se fija con `pattern: '^[A-Z0-9][A-Z0-9-]*$'` en el parámetro `sku` (solo mayúsculas, dígitos y guiones), verificado por el lint de ejemplos (Tarea 1 y 2).
2. **`occurredAt` sin milisegundos** (`Instant.toString()` de Java omite la fracción cuando vale cero, por ejemplo `2026-10-10T15:05:00Z`). Se espera que el schema lo rechace, de modo que la fase 4 formatee siempre con 3 decimales. Se fija con `examples/invalid-missing-millis.json` (Tarea 3).
3. **Campo extra en el evento.** El productor es estricto (`additionalProperties: false`). Se fija con `examples/invalid-extra-field.json` (Tarea 3). El consumidor tolerante se prueba en la fase 5.
4. **Mensaje publicado con una routing key distinta** (por ejemplo, un error tipográfico). Se espera `routed: false`, que con `mandatory = true` el outbox trata como fallo. Se fija en la verificación de topología (Tarea 4).
5. **Mensaje rechazado sin reencolar que no llega a la DLQ** (por falta de `x-dead-letter-routing-key` o del binding de la DLQ). Se espera que termine en `catalog.inventory-stock-changed.dlq`. Se fija en la verificación de topología (Tarea 4).

---

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `docs/contracts/redocly.yaml` | Reglas de lint de los OpenAPI |
| `docs/contracts/openapi/inventory-service.yaml` | Contrato REST de Inventario |
| `docs/contracts/openapi/catalog-service.yaml` | Contrato REST de Catálogo |
| `docs/contracts/events/inventory-stock-changed.schema.json` | JSON Schema del evento |
| `docs/contracts/events/examples/valid.json` | Ejemplo válido (pruebas de contrato de las fases 4 y 5) |
| `docs/contracts/events/examples/invalid-*.json`, `invalid-malformed.txt` | Ejemplos inválidos (pruebas y escenario 7) |
| `docs/contracts/README.md` | Índice de los contratos y cómo verificarlos |
| `infra/rabbitmq/definitions.json` | Topología de RabbitMQ (usuario, vhost, exchanges, colas, bindings) |
| `infra/rabbitmq/rabbitmq.conf` | Indica a RabbitMQ que cargue `definitions.json` al arrancar |
| `scripts/contracts/lint-openapi.sh` | Lint de los OpenAPI con Redocly |
| `scripts/contracts/validate-event-schema.sh` | Valida el schema y sus ejemplos |
| `scripts/contracts/verify-rabbitmq-topology.sh` | Arranca RabbitMQ con la topología y verifica su comportamiento |
| `scripts/verify-contracts.sh` | Ejecuta las tres verificaciones |

---

### Task 1: Lint de OpenAPI y contrato de inventory-service

**Files:**
- Create: `scripts/contracts/lint-openapi.sh`
- Create: `docs/contracts/redocly.yaml`
- Create: `docs/contracts/openapi/inventory-service.yaml`

**Interfaces:**
- Consumes: nada.
- Produces: `scripts/contracts/lint-openapi.sh` (sin argumentos; exit 0 si todos los OpenAPI de su lista pasan el lint). La lista `FILES` se amplía en la Tarea 2.

- [ ] **Step 1: Escribir el script de verificación**

`scripts/contracts/lint-openapi.sh`:

```bash
#!/usr/bin/env bash
# Valida los contratos OpenAPI con Redocly CLI (ejecutado en Docker).
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
REDOCLY_IMAGE="redocly/cli:2.62.1"
FILES=(
  "inventory-service.yaml"
)

status=0
for file in "${FILES[@]}"; do
  if [[ ! -f "$ROOT/docs/contracts/openapi/$file" ]]; then
    echo "FAIL: falta docs/contracts/openapi/$file"
    status=1
    continue
  fi
  if docker run --rm -v "$ROOT/docs/contracts:/spec:z" "$REDOCLY_IMAGE" \
      lint "/spec/openapi/$file" --config /spec/redocly.yaml --format=stylish; then
    echo "PASS: $file"
  else
    echo "FAIL: $file"
    status=1
  fi
done
exit $status
```

Run: `chmod +x scripts/contracts/lint-openapi.sh`

- [ ] **Step 2: Ejecutar el script y verificar que falla**

Run: `scripts/contracts/lint-openapi.sh`
Expected: `FAIL: falta docs/contracts/openapi/inventory-service.yaml` y exit code 1.

- [ ] **Step 3: Escribir la configuración de lint**

`docs/contracts/redocly.yaml`:

```yaml
# Configuración de lint para los contratos OpenAPI (Redocly CLI).
extends:
  - recommended
rules:
  # Proyecto académico sin licencia declarada.
  info-license: off
  # Los servicios solo se ejecutan en local (Docker Compose).
  no-server-example.com: off
  # Listar productos no tiene errores 4xx legítimos.
  operation-4xx-response: off
  # Los ejemplos deben cumplir sus schemas y no debe haber componentes sin usar.
  no-invalid-media-type-examples: error
  no-unused-components: error
```

- [ ] **Step 4: Escribir el contrato de Inventario**

`docs/contracts/openapi/inventory-service.yaml`:

```yaml
openapi: 3.1.0
info:
  title: Inventory Service API
  version: 1.0.0
  description: |
    Servicio de Inventario: única fuente de verdad del stock. Registra ventas de los canales
    POS y ONLINE y, por cada venta confirmada, publica el evento `InventoryStockChanged`
    (patrón Event-Driven Messaging con Transactional Outbox).

    Es un servicio interno: los canales consultan disponibilidad a través del Servicio de Catálogo.
servers:
  - url: http://localhost:8081
    description: Entorno local (Docker Compose)
tags:
  - name: Sales
    description: Registro de ventas
  - name: Stock
    description: Consulta del stock (uso interno)
  - name: Diagnostics
    description: Endpoints de diagnóstico para la demo
paths:
  /api/v1/inventory/{sku}/sales:
    post:
      tags: [Sales]
      operationId: registerSale
      summary: Registrar una venta
      description: |
        Descuenta el stock del SKU y, en la misma transacción, guarda el evento
        `InventoryStockChanged` en el outbox. El `saleId` de la respuesta es el `eventId`
        del evento generado.
      security:
        - bearerAuth: []
      parameters:
        - $ref: '#/components/parameters/Sku'
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/SaleRequest'
            example:
              quantity: 1
              channel: POS
      responses:
        '201':
          description: Venta confirmada
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SaleResponse'
              example:
                saleId: 7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73
                sku: TENIS-RUNNER-42
                quantity: 1
                channel: POS
                remainingStock: 4
                version: 8
                occurredAt: '2026-10-10T15:04:05.123Z'
        '400':
          $ref: '#/components/responses/ValidationError'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '404':
          $ref: '#/components/responses/NotFound'
        '409':
          description: Stock insuficiente o modificación concurrente
          content:
            application/problem+json:
              schema:
                oneOf:
                  - $ref: '#/components/schemas/InsufficientStockProblem'
                  - $ref: '#/components/schemas/Problem'
              examples:
                insufficientStock:
                  summary: Stock insuficiente
                  value:
                    type: https://api.retail-demo.local/problems/insufficient-stock
                    title: Insufficient stock
                    status: 409
                    detail: SKU TENIS-RUNNER-42 has 0 units available, 1 requested
                    instance: /api/v1/inventory/TENIS-RUNNER-42/sales
                    availableStock: 0
                    requestedQuantity: 1
                concurrentModification:
                  summary: Reintentos agotados por bloqueo optimista
                  value:
                    type: https://api.retail-demo.local/problems/concurrent-modification
                    title: Concurrent modification
                    status: 409
                    detail: Stock of SKU TENIS-RUNNER-42 changed concurrently, please retry
                    instance: /api/v1/inventory/TENIS-RUNNER-42/sales
        '500':
          $ref: '#/components/responses/InternalError'
  /api/v1/inventory/{sku}:
    get:
      tags: [Stock]
      operationId: getStock
      summary: Consultar el stock actual de un SKU
      description: Uso interno (operadores). Los canales consultan disponibilidad en el Catálogo.
      security:
        - bearerAuth: []
      parameters:
        - $ref: '#/components/parameters/Sku'
      responses:
        '200':
          description: Stock actual
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/StockResponse'
              example:
                sku: TENIS-RUNNER-42
                stock: 4
                version: 8
                updatedAt: '2026-10-10T15:04:05.123Z'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '403':
          $ref: '#/components/responses/Forbidden'
        '404':
          $ref: '#/components/responses/NotFound'
        '500':
          $ref: '#/components/responses/InternalError'
  /api/v1/inventory/outbox:
    get:
      tags: [Diagnostics]
      operationId: listOutboxEvents
      summary: Listar eventos del outbox
      description: |
        Endpoint de diagnóstico para la demo (escenario "RabbitMQ caído"). Devuelve como máximo
        50 eventos, los más recientes primero. En un entorno real estaría protegido o no existiría.
      security: []
      parameters:
        - name: status
          in: query
          required: false
          description: Filtra por estado. Sin este parámetro se devuelven ambos estados.
          schema:
            $ref: '#/components/schemas/OutboxStatus'
      responses:
        '200':
          description: Eventos del outbox
          content:
            application/json:
              schema:
                type: array
                maxItems: 50
                items:
                  $ref: '#/components/schemas/OutboxEvent'
              example:
                - eventId: 7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73
                  sku: TENIS-RUNNER-42
                  status: PENDING
                  attempts: 3
                  lastError: Connection refused
                  occurredAt: '2026-10-10T15:04:05.123Z'
                  publishedAt: null
        '400':
          $ref: '#/components/responses/ValidationError'
        '500':
          $ref: '#/components/responses/InternalError'
components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
      description: |
        JWT HS256 emitido por `scripts/generate-token.sh`. Claims: `iss` = `retail-demo-auth`,
        `sub`, `scope` (permisos separados por espacio: `inventory:sell`, `inventory:read`) y `exp`.
  parameters:
    Sku:
      name: sku
      in: path
      required: true
      description: Código del producto
      schema:
        type: string
        pattern: '^[A-Z0-9][A-Z0-9-]*$'
      example: TENIS-RUNNER-42
  schemas:
    Channel:
      type: string
      enum: [POS, ONLINE]
      description: Canal de venta
    OutboxStatus:
      type: string
      enum: [PENDING, PUBLISHED]
    SaleRequest:
      type: object
      required: [quantity, channel]
      additionalProperties: false
      properties:
        quantity:
          type: integer
          minimum: 1
        channel:
          $ref: '#/components/schemas/Channel'
    SaleResponse:
      type: object
      required: [saleId, sku, quantity, channel, remainingStock, version, occurredAt]
      properties:
        saleId:
          type: string
          format: uuid
          description: Igual al `eventId` del evento `InventoryStockChanged` generado
        sku:
          type: string
        quantity:
          type: integer
          minimum: 1
        channel:
          $ref: '#/components/schemas/Channel'
        remainingStock:
          type: integer
          minimum: 0
        version:
          type: integer
          minimum: 1
          description: Versión del stock tras la venta (igual al `stockVersion` del evento)
        occurredAt:
          type: string
          format: date-time
    StockResponse:
      type: object
      required: [sku, stock, version, updatedAt]
      properties:
        sku:
          type: string
        stock:
          type: integer
          minimum: 0
        version:
          type: integer
          minimum: 1
        updatedAt:
          type: string
          format: date-time
    OutboxEvent:
      type: object
      required: [eventId, sku, status, attempts, lastError, occurredAt, publishedAt]
      properties:
        eventId:
          type: string
          format: uuid
        sku:
          type: string
        status:
          $ref: '#/components/schemas/OutboxStatus'
        attempts:
          type: integer
          minimum: 0
        lastError:
          type: [string, 'null']
        occurredAt:
          type: string
          format: date-time
        publishedAt:
          type: [string, 'null']
          format: date-time
    Problem:
      type: object
      description: Detalle de error según RFC 9457
      required: [type, title, status]
      properties:
        type:
          type: string
          format: uri
        title:
          type: string
        status:
          type: integer
        detail:
          type: string
        instance:
          type: string
    ValidationProblem:
      allOf:
        - $ref: '#/components/schemas/Problem'
        - type: object
          required: [errors]
          properties:
            errors:
              type: array
              items:
                type: object
                required: [field, message]
                properties:
                  field:
                    type: string
                  message:
                    type: string
    InsufficientStockProblem:
      allOf:
        - $ref: '#/components/schemas/Problem'
        - type: object
          required: [availableStock, requestedQuantity]
          properties:
            availableStock:
              type: integer
              minimum: 0
            requestedQuantity:
              type: integer
              minimum: 1
  responses:
    ValidationError:
      description: Petición inválida
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/ValidationProblem'
          example:
            type: https://api.retail-demo.local/problems/validation-error
            title: Validation error
            status: 400
            detail: The request has invalid fields
            instance: /api/v1/inventory/TENIS-RUNNER-42/sales
            errors:
              - field: quantity
                message: must be greater than or equal to 1
    Unauthorized:
      description: Sin token, token inválido o expirado
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
          example:
            type: https://api.retail-demo.local/problems/unauthorized
            title: Unauthorized
            status: 401
            detail: A valid bearer token is required
            instance: /api/v1/inventory/TENIS-RUNNER-42/sales
    Forbidden:
      description: El token no tiene el permiso requerido
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
          example:
            type: https://api.retail-demo.local/problems/forbidden
            title: Forbidden
            status: 403
            detail: The token lacks the required scope
            instance: /api/v1/inventory/TENIS-RUNNER-42/sales
    NotFound:
      description: SKU inexistente
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
          example:
            type: https://api.retail-demo.local/problems/not-found
            title: Not found
            status: 404
            detail: SKU UNKNOWN-SKU does not exist
            instance: /api/v1/inventory/UNKNOWN-SKU
    InternalError:
      description: Error interno (sin detalles de implementación)
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
          example:
            type: https://api.retail-demo.local/problems/internal-error
            title: Internal error
            status: 500
            detail: An unexpected error occurred
            instance: /api/v1/inventory/TENIS-RUNNER-42/sales
```

- [ ] **Step 5: Ejecutar el script y verificar que pasa**

Run: `scripts/contracts/lint-openapi.sh`
Expected: `Woohoo! Your API description is valid. 🎉`, sin warnings, `PASS: inventory-service.yaml` y exit code 0.

- [ ] **Step 6: Verificar que el lint detecta un ejemplo que no cumple el schema**

Cambiar temporalmente en `inventory-service.yaml` el ejemplo de la respuesta `201` de `remainingStock: 4` a `remainingStock: -1` y ejecutar `scripts/contracts/lint-openapi.sh`.
Expected: `error  no-invalid-media-type-examples` y exit code 1.
Restaurar `remainingStock: 4` y ejecutar de nuevo el script. Expected: exit code 0.

- [ ] **Step 7: Commit**

```bash
git add scripts/contracts/lint-openapi.sh docs/contracts/redocly.yaml docs/contracts/openapi/inventory-service.yaml
git commit -m "feat: add inventory-service OpenAPI contract and lint script"
```

---

### Task 2: Contrato de catalog-service

**Files:**
- Modify: `scripts/contracts/lint-openapi.sh` (lista `FILES`)
- Create: `docs/contracts/openapi/catalog-service.yaml`

**Interfaces:**
- Consumes: `scripts/contracts/lint-openapi.sh` y `docs/contracts/redocly.yaml` de la Tarea 1.
- Produces: `docs/contracts/openapi/catalog-service.yaml`, lintado por el mismo script.

- [ ] **Step 1: Agregar el contrato a la lista del script**

En `scripts/contracts/lint-openapi.sh`, reemplazar el bloque `FILES` por:

```bash
FILES=(
  "inventory-service.yaml"
  "catalog-service.yaml"
)
```

- [ ] **Step 2: Ejecutar el script y verificar que falla**

Run: `scripts/contracts/lint-openapi.sh`
Expected: `PASS: inventory-service.yaml`, `FAIL: falta docs/contracts/openapi/catalog-service.yaml` y exit code 1.

- [ ] **Step 3: Escribir el contrato del Catálogo**

`docs/contracts/openapi/catalog-service.yaml`:

```yaml
openapi: 3.1.0
info:
  title: Catalog Service API
  version: 1.0.0
  description: |
    Servicio de Catálogo: datos de producto y vista de disponibilidad de solo lectura para los
    canales digitales. La disponibilidad se actualiza exclusivamente con los eventos
    `InventoryStockChanged`; este servicio nunca consulta al Servicio de Inventario.
servers:
  - url: http://localhost:8082
    description: Entorno local (Docker Compose)
security: []
tags:
  - name: Products
    description: Consulta de productos y disponibilidad
paths:
  /api/v1/catalog/products:
    get:
      tags: [Products]
      operationId: listProducts
      summary: Listar productos con su disponibilidad
      description: Devuelve todos los productos, sin paginación ni filtros.
      responses:
        '200':
          description: Lista de productos
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/Product'
              example:
                - sku: TENIS-RUNNER-42
                  name: Tenis Runner talla 42
                  description: Tenis para correr, malla transpirable
                  price: 289900
                  currency: COP
                  availableStock: 4
                  inStock: true
                  updatedAt: '2026-10-10T15:04:05.512Z'
                  lastEventLatencyMs: 389
                - sku: CAMISETA-DRYFIT-M
                  name: Camiseta Dry-Fit talla M
                  description: Camiseta deportiva de secado rápido
                  price: 79900
                  currency: COP
                  availableStock: 0
                  inStock: false
                  updatedAt: '2026-10-10T14:00:00.000Z'
                  lastEventLatencyMs: null
        '500':
          $ref: '#/components/responses/InternalError'
  /api/v1/catalog/products/{sku}:
    get:
      tags: [Products]
      operationId: getProduct
      summary: Consultar un producto
      parameters:
        - name: sku
          in: path
          required: true
          description: Código del producto
          schema:
            type: string
            pattern: '^[A-Z0-9][A-Z0-9-]*$'
          example: TENIS-RUNNER-42
      responses:
        '200':
          description: Producto con su disponibilidad
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Product'
              example:
                sku: TENIS-RUNNER-42
                name: Tenis Runner talla 42
                description: Tenis para correr, malla transpirable
                price: 289900
                currency: COP
                availableStock: 4
                inStock: true
                updatedAt: '2026-10-10T15:04:05.512Z'
                lastEventLatencyMs: 389
        '404':
          description: SKU inexistente
          content:
            application/problem+json:
              schema:
                $ref: '#/components/schemas/Problem'
              example:
                type: https://api.retail-demo.local/problems/not-found
                title: Not found
                status: 404
                detail: SKU UNKNOWN-SKU does not exist
                instance: /api/v1/catalog/products/UNKNOWN-SKU
        '500':
          $ref: '#/components/responses/InternalError'
components:
  schemas:
    Product:
      type: object
      required: [sku, name, description, price, currency, availableStock, inStock, updatedAt, lastEventLatencyMs]
      properties:
        sku:
          type: string
        name:
          type: string
        description:
          type: string
        price:
          type: integer
          minimum: 0
          description: Precio en pesos colombianos, sin decimales
        currency:
          type: string
          const: COP
        availableStock:
          type: integer
          minimum: 0
        inStock:
          type: boolean
          description: '`true` si `availableStock` > 0'
        updatedAt:
          type: string
          format: date-time
          description: Momento en que el Catálogo aplicó el último cambio (o el sembrado inicial)
        lastEventLatencyMs:
          type: [integer, 'null']
          minimum: 0
          description: |
            `updatedAt` − `occurredAt` del último evento aplicado, en milisegundos.
            `null` si el producto no ha recibido eventos.
    Problem:
      type: object
      description: Detalle de error según RFC 9457
      required: [type, title, status]
      properties:
        type:
          type: string
          format: uri
        title:
          type: string
        status:
          type: integer
        detail:
          type: string
        instance:
          type: string
  responses:
    InternalError:
      description: Error interno (sin detalles de implementación)
      content:
        application/problem+json:
          schema:
            $ref: '#/components/schemas/Problem'
          example:
            type: https://api.retail-demo.local/problems/internal-error
            title: Internal error
            status: 500
            detail: An unexpected error occurred
            instance: /api/v1/catalog/products
```

- [ ] **Step 4: Ejecutar el script y verificar que pasa**

Run: `scripts/contracts/lint-openapi.sh`
Expected: ambos archivos `PASS`, sin warnings, exit code 0.

- [ ] **Step 5: Commit**

```bash
git add scripts/contracts/lint-openapi.sh docs/contracts/openapi/catalog-service.yaml
git commit -m "feat: add catalog-service OpenAPI contract"
```

---

### Task 3: JSON Schema del evento y ejemplos

**Files:**
- Create: `scripts/contracts/validate-event-schema.sh`
- Create: `docs/contracts/events/inventory-stock-changed.schema.json`
- Create: `docs/contracts/events/examples/valid.json`
- Create: `docs/contracts/events/examples/invalid-negative-stock.json`
- Create: `docs/contracts/events/examples/invalid-missing-field.json`
- Create: `docs/contracts/events/examples/invalid-zero-version.json`
- Create: `docs/contracts/events/examples/invalid-unknown-channel.json`
- Create: `docs/contracts/events/examples/invalid-missing-millis.json`
- Create: `docs/contracts/events/examples/invalid-extra-field.json`
- Create: `docs/contracts/events/examples/invalid-malformed.txt`

**Interfaces:**
- Consumes: nada.
- Produces: el schema y los ejemplos que usan las pruebas de contrato de las fases 4 (`valid.json` como referencia del productor) y 5 (`valid.json` debe deserializarse; `invalid-*` deben ir a la DLQ). `scripts/contracts/validate-event-schema.sh` (sin argumentos; exit 0 si el schema es válido, `valid.json` pasa y todos los `invalid-*` fallan).

- [ ] **Step 1: Escribir el script de verificación**

`scripts/contracts/validate-event-schema.sh`:

```bash
#!/usr/bin/env bash
# Valida el JSON Schema del evento InventoryStockChanged y sus ejemplos (check-jsonschema en Docker).
# - El schema debe cumplir el metaschema draft 2020-12.
# - examples/valid.json debe validar.
# - Cada examples/invalid-* debe fallar.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
EVENTS_DIR="$ROOT/docs/contracts/events"

if [[ ! -f "$EVENTS_DIR/inventory-stock-changed.schema.json" ]]; then
  echo "FAIL: falta docs/contracts/events/inventory-stock-changed.schema.json"
  exit 1
fi

docker run --rm -v "$EVENTS_DIR:/events:z" -w /events python:3.13-slim sh -c '
  pip install --quiet --root-user-action=ignore check-jsonschema==0.38.2 >/dev/null 2>&1
  SCHEMA=inventory-stock-changed.schema.json
  status=0

  if check-jsonschema --check-metaschema "$SCHEMA" >/dev/null; then
    echo "PASS: schema cumple el metaschema"
  else
    echo "FAIL: schema no cumple el metaschema"; status=1
  fi

  if check-jsonschema --schemafile "$SCHEMA" examples/valid.json >/dev/null; then
    echo "PASS: examples/valid.json es valido"
  else
    echo "FAIL: examples/valid.json deberia ser valido"; status=1
  fi

  found=0
  for f in examples/invalid-*; do
    [ -e "$f" ] || continue
    found=1
    if check-jsonschema --schemafile "$SCHEMA" --default-filetype json "$f" >/dev/null 2>&1; then
      echo "FAIL: $f deberia ser invalido"; status=1
    else
      echo "PASS: $f es invalido"
    fi
  done
  if [ "$found" -eq 0 ]; then
    echo "FAIL: no hay ejemplos invalidos"; status=1
  fi

  exit $status
'
```

Run: `chmod +x scripts/contracts/validate-event-schema.sh`

- [ ] **Step 2: Ejecutar el script y verificar que falla**

Run: `scripts/contracts/validate-event-schema.sh`
Expected: `FAIL: falta docs/contracts/events/inventory-stock-changed.schema.json` y exit code 1.

- [ ] **Step 3: Escribir el schema**

`docs/contracts/events/inventory-stock-changed.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://api.retail-demo.local/events/inventory-stock-changed.schema.json",
  "title": "InventoryStockChanged",
  "description": "Evento publicado por inventory-service cuando cambia el stock de un SKU. Transporta el stock absoluto resultante (Event-Carried State Transfer).",
  "type": "object",
  "required": ["eventId", "eventType", "sku", "newStock", "stockVersion", "channel", "occurredAt"],
  "additionalProperties": false,
  "properties": {
    "eventId": {
      "description": "Identificador único del evento; igual al saleId de la venta. Base de la idempotencia del consumidor.",
      "type": "string",
      "format": "uuid"
    },
    "eventType": {
      "description": "Tipo de evento.",
      "const": "InventoryStockChanged"
    },
    "sku": {
      "description": "Producto afectado.",
      "type": "string",
      "minLength": 1
    },
    "newStock": {
      "description": "Stock absoluto resultante.",
      "type": "integer",
      "minimum": 0
    },
    "stockVersion": {
      "description": "Versión del stock tras el cambio; el consumidor descarta versiones menores o iguales a la ya aplicada.",
      "type": "integer",
      "minimum": 1
    },
    "channel": {
      "description": "Canal que originó el cambio.",
      "enum": ["POS", "ONLINE"]
    },
    "occurredAt": {
      "description": "Momento de la venta, ISO-8601 en UTC con milisegundos.",
      "type": "string",
      "format": "date-time",
      "pattern": "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$"
    }
  }
}
```

- [ ] **Step 4: Escribir el ejemplo válido**

`docs/contracts/events/examples/valid.json`:

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

- [ ] **Step 5: Ejecutar el script y verificar que falla por falta de ejemplos inválidos**

Run: `scripts/contracts/validate-event-schema.sh`
Expected: `PASS: schema cumple el metaschema`, `PASS: examples/valid.json es valido`, `FAIL: no hay ejemplos invalidos` y exit code 1.

- [ ] **Step 6: Escribir los ejemplos inválidos**

Cada archivo viola exactamente una regla de la spec §5.4 (o de la Review Focus).

`docs/contracts/events/examples/invalid-negative-stock.json` (`newStock` negativo):

```json
{
  "eventId": "0b6f2c1e-8d4a-4f3b-9c2e-5a7d1e9f3b60",
  "eventType": "InventoryStockChanged",
  "sku": "TENIS-RUNNER-42",
  "newStock": -1,
  "stockVersion": 9,
  "channel": "POS",
  "occurredAt": "2026-10-10T15:05:00.000Z"
}
```

`docs/contracts/events/examples/invalid-missing-field.json` (falta `sku`):

```json
{
  "eventId": "5d1e7a2b-3c4f-4a6b-8d9e-0f1a2b3c4d5e",
  "eventType": "InventoryStockChanged",
  "newStock": 3,
  "stockVersion": 10,
  "channel": "ONLINE",
  "occurredAt": "2026-10-10T15:06:00.000Z"
}
```

`docs/contracts/events/examples/invalid-zero-version.json` (`stockVersion` < 1):

```json
{
  "eventId": "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d",
  "eventType": "InventoryStockChanged",
  "sku": "TENIS-RUNNER-42",
  "newStock": 3,
  "stockVersion": 0,
  "channel": "ONLINE",
  "occurredAt": "2026-10-10T15:07:00.000Z"
}
```

`docs/contracts/events/examples/invalid-unknown-channel.json` (canal desconocido; el enum distingue mayúsculas):

```json
{
  "eventId": "2f4e6a8c-0b1d-4e3f-a5b7-c9d1e3f5a7b9",
  "eventType": "InventoryStockChanged",
  "sku": "TENIS-RUNNER-42",
  "newStock": 3,
  "stockVersion": 11,
  "channel": "pos",
  "occurredAt": "2026-10-10T15:08:00.000Z"
}
```

`docs/contracts/events/examples/invalid-missing-millis.json` (`occurredAt` sin milisegundos, como lo produce `Instant.toString()` cuando la fracción es cero):

```json
{
  "eventId": "6c5b4a39-2817-4f6e-8d5c-4b3a29180f7e",
  "eventType": "InventoryStockChanged",
  "sku": "TENIS-RUNNER-42",
  "newStock": 3,
  "stockVersion": 12,
  "channel": "POS",
  "occurredAt": "2026-10-10T15:09:00Z"
}
```

`docs/contracts/events/examples/invalid-extra-field.json` (campo no definido; el productor es estricto):

```json
{
  "eventId": "8e7d6c5b-4a39-4281-9f0e-1d2c3b4a5968",
  "eventType": "InventoryStockChanged",
  "sku": "TENIS-RUNNER-42",
  "newStock": 3,
  "stockVersion": 13,
  "channel": "POS",
  "occurredAt": "2026-10-10T15:10:00.000Z",
  "previousStock": 4
}
```

`docs/contracts/events/examples/invalid-malformed.txt` (JSON truncado; una sola línea):

```text
{"eventId": "3c9d
```

- [ ] **Step 7: Ejecutar el script y verificar que pasa**

Run: `scripts/contracts/validate-event-schema.sh`
Expected: `PASS` para el metaschema, para `valid.json` y para los 7 archivos `invalid-*`; exit code 0.

- [ ] **Step 8: Commit**

```bash
git add scripts/contracts/validate-event-schema.sh docs/contracts/events
git commit -m "feat: add InventoryStockChanged event schema and examples"
```

---

### Task 4: Topología de RabbitMQ

**Files:**
- Create: `scripts/contracts/verify-rabbitmq-topology.sh`
- Create: `infra/rabbitmq/definitions.json`
- Create: `infra/rabbitmq/rabbitmq.conf`
- Delete: `infra/.gitkeep`

**Interfaces:**
- Consumes: nada.
- Produces: `infra/rabbitmq/definitions.json` e `infra/rabbitmq/rabbitmq.conf`, que la fase 3 monta en el `docker-compose` y las fases 4 y 5 cargan en Testcontainers. Credenciales de demo: usuario `retail`, contraseña `retail-demo` (no existe el usuario `guest`). `scripts/contracts/verify-rabbitmq-topology.sh` (sin argumentos; exit 0 si la topología se carga y se comporta como la spec §6).

Notas:
- Con `load_definitions`, RabbitMQ no crea el usuario `guest` ni el vhost por defecto; por eso `definitions.json` incluye usuario, vhost y permisos.
- `password_hash` es `base64(salt + sha256(salt + "retail-demo"))` con salt `a1b2c3d4` (algoritmo `rabbit_password_hashing_sha256`). Para regenerarlo:
  `python3 -c "import hashlib,base64; s=bytes.fromhex('a1b2c3d4'); print(base64.b64encode(s+hashlib.sha256(s+b'retail-demo').digest()).decode())"`

- [ ] **Step 1: Escribir el script de verificación**

`scripts/contracts/verify-rabbitmq-topology.sh`:

```bash
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
```

Run: `chmod +x scripts/contracts/verify-rabbitmq-topology.sh`

- [ ] **Step 2: Ejecutar el script y verificar que falla**

Run: `scripts/contracts/verify-rabbitmq-topology.sh`
Expected: `FAIL: falta infra/rabbitmq/definitions.json` y exit code 1.

- [ ] **Step 3: Escribir la configuración de RabbitMQ**

`infra/rabbitmq/rabbitmq.conf`:

```ini
# Carga la topología (usuario, vhost, exchanges, colas y bindings) al arrancar el broker.
load_definitions = /etc/rabbitmq/definitions.json
```

- [ ] **Step 4: Escribir la topología**

`infra/rabbitmq/definitions.json`:

```json
{
  "users": [
    {
      "name": "retail",
      "password_hash": "obLD1NheiS/aTagWaX9HNW3wzbbKSVmdvHBmKzWxlnlmfxnG",
      "hashing_algorithm": "rabbit_password_hashing_sha256",
      "tags": [
        "administrator"
      ]
    }
  ],
  "vhosts": [
    {
      "name": "/"
    }
  ],
  "permissions": [
    {
      "user": "retail",
      "vhost": "/",
      "configure": ".*",
      "write": ".*",
      "read": ".*"
    }
  ],
  "exchanges": [
    {
      "name": "inventory.events",
      "vhost": "/",
      "type": "topic",
      "durable": true,
      "auto_delete": false,
      "internal": false,
      "arguments": {}
    },
    {
      "name": "catalog.dlx",
      "vhost": "/",
      "type": "direct",
      "durable": true,
      "auto_delete": false,
      "internal": false,
      "arguments": {}
    }
  ],
  "queues": [
    {
      "name": "catalog.inventory-stock-changed",
      "vhost": "/",
      "durable": true,
      "auto_delete": false,
      "arguments": {
        "x-queue-type": "classic",
        "x-dead-letter-exchange": "catalog.dlx",
        "x-dead-letter-routing-key": "catalog.inventory-stock-changed.dlq"
      }
    },
    {
      "name": "catalog.inventory-stock-changed.dlq",
      "vhost": "/",
      "durable": true,
      "auto_delete": false,
      "arguments": {
        "x-queue-type": "classic"
      }
    }
  ],
  "bindings": [
    {
      "source": "inventory.events",
      "vhost": "/",
      "destination": "catalog.inventory-stock-changed",
      "destination_type": "queue",
      "routing_key": "inventory.stock.changed",
      "arguments": {}
    },
    {
      "source": "catalog.dlx",
      "vhost": "/",
      "destination": "catalog.inventory-stock-changed.dlq",
      "destination_type": "queue",
      "routing_key": "catalog.inventory-stock-changed.dlq",
      "arguments": {}
    }
  ]
}
```

Run: `git rm -q infra/.gitkeep`

- [ ] **Step 5: Ejecutar el script y verificar que pasa**

Run: `scripts/contracts/verify-rabbitmq-topology.sh`
Expected: 9 líneas `PASS` y exit code 0. La primera ejecución descarga la imagen `rabbitmq:4.3-management`.

- [ ] **Step 6: Commit**

```bash
git add scripts/contracts/verify-rabbitmq-topology.sh infra/rabbitmq
git commit -m "feat: add RabbitMQ topology definitions"
```

---

### Task 5: Verificación conjunta e índice de contratos

**Files:**
- Create: `scripts/verify-contracts.sh`
- Create: `docs/contracts/README.md`
- Modify: `README.md` (sección "Estructura")

**Interfaces:**
- Consumes: los tres scripts de `scripts/contracts/` (Tareas 1, 3 y 4).
- Produces: `scripts/verify-contracts.sh` (sin argumentos; exit 0 solo si las tres verificaciones pasan). Las fases siguientes lo ejecutan para comprobar que no rompieron los contratos.

- [ ] **Step 1: Escribir el script conjunto**

`scripts/verify-contracts.sh`:

```bash
#!/usr/bin/env bash
# Ejecuta todas las verificaciones de contratos y resume el resultado.
set -uo pipefail

ROOT="$(git rev-parse --show-toplevel)"
CHECKS=(
  "lint-openapi.sh"
  "validate-event-schema.sh"
  "verify-rabbitmq-topology.sh"
)

failed=()
for check in "${CHECKS[@]}"; do
  echo "=== $check ==="
  if ! "$ROOT/scripts/contracts/$check"; then
    failed+=("$check")
  fi
  echo
done

if (( ${#failed[@]} > 0 )); then
  echo "Contratos: FALLÓ (${failed[*]})"
  exit 1
fi
echo "Contratos: OK"
```

Run: `chmod +x scripts/verify-contracts.sh`

- [ ] **Step 2: Ejecutar el script conjunto y verificar que pasa**

Run: `scripts/verify-contracts.sh`
Expected: las tres secciones con sus `PASS` y la última línea `Contratos: OK`; exit code 0.

- [ ] **Step 3: Escribir el índice de contratos**

`docs/contracts/README.md`:

````markdown
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
````

- [ ] **Step 4: Actualizar la estructura del README principal**

En `README.md`, reemplazar el bloque de la sección "Estructura" por:

````markdown
## Estructura
```
docs/contracts/           Contratos: OpenAPI, JSON Schema del evento (ver su README)
docs/superpowers/specs/   Especificaciones de diseño
docs/superpowers/plans/   Planes de implementación
infra/rabbitmq/           Topología y configuración de RabbitMQ
scripts/                  Scripts de verificación y utilidades
```

Para verificar los contratos: `scripts/verify-contracts.sh` (requiere Docker).
````

- [ ] **Step 5: Ejecutar de nuevo la verificación conjunta**

Run: `scripts/verify-contracts.sh`
Expected: `Contratos: OK`, exit code 0.

- [ ] **Step 6: Commit**

```bash
git add scripts/verify-contracts.sh docs/contracts/README.md README.md
git commit -m "docs: add contracts index and combined verification script"
```
