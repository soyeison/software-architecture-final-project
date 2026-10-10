# Spec — Diseño de la solución global

- **Fecha:** 2026-10-09
- **Fase:** 1 (diseño de la solución completa)
- **Estado:** pendiente de revisión

## 1. Contexto y objetivo

Una empresa de retail vende por tiendas físicas (POS) y por canales digitales (web y app). Las
ventas descuentan stock en tiempo real, pero el catálogo digital se actualiza por lotes cada 30
minutos. Durante ese intervalo los clientes ven disponibles productos agotados, lo que causa
sobreventas, cancelaciones y reclamaciones.

La implementación debe evidenciar, con servicios REST funcionales, cómo el patrón
**Event-Driven Messaging** (Erl, 2009), complementado con **Asynchronous Queuing** y
**Reliable Messaging**, transforma esa arquitectura: los cambios de stock se propagan mediante
eventos en lugar de lotes periódicos.

### Criterios de éxito

1. Una venta por POS y una venta online se reflejan en el Catálogo en menos de 1 segundo.
2. Los 11 escenarios de la sección 4 se comportan como se especifica y cada uno tiene al menos
   una prueba automatizada que lo demuestra.
3. Se obtiene una medición de latencia reproducible para el documento final.
4. Existe un guion de video de 5–10 minutos, presentado por los cuatro integrantes.

## 2. Alcance

| Componente | Decisión |
|---|---|
| `inventory-service` | Se construye. Productor y única fuente de verdad del stock. |
| `catalog-service` | Se construye. Consumidor con vista de disponibilidad propia. |
| RabbitMQ | Se construye (configuración y topología). |
| Servicio de Ventas | Simulado: endpoint de venta en Inventario + scripts por canal. |
| API Gateway, canales de venta | Fuera de alcance; solo se describen en el documento. |
| Interfaz gráfica | Fuera de alcance; la demo usa scripts, logs y la consola de RabbitMQ. |
| Reproducción del modo por lotes ("antes") | Fuera de alcance; se explica con el caso real de 30 minutos. |
| Despliegue en la nube | Fuera de alcance; todo se ejecuta en local con Docker Compose. |

## 3. Arquitectura

```
 scripts POS / ONLINE ──REST+JWT──► inventory-service ──(outbox)──► RabbitMQ ──► catalog-service ◄──REST── consulta
                                         │                                              │
                                    inventory-db                                   catalog-db
```

Los servicios se coordinan por **coreografía** mediante eventos; no hay orquestador central.

### 3.1 inventory-service

- Puerto 8081, base de datos PostgreSQL propia (`inventory-db`).
- Única fuente de verdad del stock. La tabla de stock tiene una columna de versión para
  bloqueo optimista.
- Endpoint de venta (ruta indicativa: `POST /api/v1/inventory/{sku}/sales`) con cuerpo
  `quantity` y `channel` (`POS` | `ONLINE`). El canal no cambia la lógica; se registra en el
  evento y en los logs para trazabilidad.
- **Transactional Outbox:** en una sola transacción descuenta el stock y escribe el evento en
  la tabla `outbox`. Un publicador interno revisa la tabla cada ~500 ms, publica con
  *publisher confirms* y marca el evento como enviado solo cuando RabbitMQ lo confirma.

### 3.2 catalog-service

- Puerto 8082, base de datos PostgreSQL propia (`catalog-db`).
- Dueño de los datos de producto (nombre, descripción, precio) y de una vista de
  disponibilidad que solo se actualiza con eventos. Nunca llama a Inventario ni lee su base.
- Consumidor con ack manual, idempotente (registro de `eventId` procesados) y que ignora
  eventos con versión menor o igual a la ya aplicada.
- Consultas públicas sin token (rutas indicativas: `GET /api/v1/catalog/products` y
  `GET /api/v1/catalog/products/{sku}`) que devuelven disponibilidad, `updatedAt` y
  `lastEventLatencyMs`.

### 3.3 RabbitMQ

- Un exchange de eventos de inventario, una cola durable para el Catálogo y una DLQ.
- Nombres, routing keys y argumentos se fijan en la fase 2 (contratos).
- Consola web de administración habilitada para la demo.

### 3.4 Evento "Cambio de Inventario"

Campos: `eventId`, `sku`, `newStock` (valor **absoluto**), `stockVersion`, `channel` y
`occurredAt`. El esquema definitivo (JSON Schema y ejemplo) se fija en la fase 2 y vive en
`docs/contracts/`.

El evento transporta el estado resultante (*Event-Carried State Transfer*), no un delta: aplicar
el mismo evento dos veces o recibir uno viejo después de uno nuevo no altera el resultado, y el
último evento basta para que el Catálogo quede correcto. No se incluye el stock anterior porque
ningún consumidor lo necesita; para logs legibles ("5 → 4"), el Catálogo usa su propio valor
previo.

### 3.5 Datos de partida

Ambos servicios arrancan con los mismos SKU sembrados: Inventario con cantidades y Catálogo con
datos de producto y disponibilidad inicial. Los SKU de la demo tienen nombres legibles (por
ejemplo `TENIS-RUNNER-42` con stock 1 para el escenario de la última unidad).

### 3.6 Seguridad

- Los endpoints de escritura de Inventario exigen un JWT firmado con HS256 y un secreto
  compartido. Sin token o con token inválido: `401`. Con un token sin permiso de venta: `403`.
- Un script genera tokens para cada canal. No hay servidor de autenticación.
- Las consultas del Catálogo son públicas.

### 3.7 Formato de errores

Los errores REST usan `application/problem+json` (RFC 9457), con el soporte nativo de Spring
Boot (`ProblemDetail`) y un `@RestControllerAdvice` por servicio para los errores de negocio.
Los `401` y `403` de Spring Security se adaptan al mismo formato.

### 3.8 Estructura del repositorio

Monorepo con dos proyectos Maven independientes (`inventory-service/`, `catalog-service/`), sin
módulo compartido. Cada servicio tiene su propia clase del evento; el contrato común vive en
`docs/contracts/`. La infraestructura vive en `infra/` y los scripts E2E en `e2e/`.

## 4. Flujo de datos y escenarios

### 4.1 Flujo principal

1. El script de POS u online llama al endpoint de venta con `sku`, `quantity`, `channel` y JWT.
2. Inventario, en una transacción, valida el stock, lo descuenta (incrementando la versión) y
   escribe el evento en `outbox`. Responde `201` con el stock resultante.
3. El publicador del outbox envía el evento a RabbitMQ y lo marca como enviado tras la
   confirmación del broker.
4. El Catálogo, en una transacción, comprueba que el `eventId` no se haya procesado, aplica el
   stock absoluto si el `stockVersion` es mayor que el guardado y registra el `eventId`. Luego hace ack.
5. La consulta del producto en el Catálogo muestra el nuevo stock, `updatedAt` y la latencia.

### 4.2 Escenarios

| # | Escenario | Comportamiento esperado |
|---|---|---|
| 1 | Venta POS | Flujo principal con `channel = POS`. |
| 2 | Venta online | Flujo principal con `channel = ONLINE`. |
| 3 | Carrera por la última unidad | Con stock 1, POS y online venden a la vez. Una obtiene `201`; la otra falla por bloqueo optimista, se reintenta, encuentra stock 0 y obtiene `409`. Se genera un solo evento. |
| 4 | Venta sin stock | `409`, sin cambios de stock y sin evento. |
| 5 | Catálogo caído | Los eventos se acumulan en la cola durable. Al reiniciar el Catálogo se procesan y el stock converge. |
| 6 | Mensaje duplicado | El `eventId` ya registrado se reconoce: ack sin cambios. |
| 7 | Mensaje inválido | Falla la deserialización o la validación: nack sin reencolar y el mensaje termina en la DLQ sin bloquear la cola. |
| 8 | RabbitMQ caído | La venta se confirma (`201`) y el evento queda pendiente en `outbox`. Cuando RabbitMQ vuelve, el publicador lo envía. Cero eventos perdidos. |
| 9 | Eventos fuera de orden | Un evento con `stockVersion` menor o igual al guardado se ignora con ack. El estado final es correcto porque el stock es absoluto. |
| 10 | Seguridad | Sin token o token inválido: `401`. Token sin permiso de venta: `403`. |
| 11 | Latencia | `updatedAt − occurredAt`, visible en la consulta y en los logs. |

### 4.3 Manejo de errores en el consumidor del Catálogo

El consumidor clasifica cada fallo según su causa, porque reintentar solo tiene sentido cuando
el problema es la infraestructura y no el mensaje:

| Tipo | Ejemplos | Comportamiento |
|---|---|---|
| **Permanente** (el mensaje es el problema) | JSON corrupto, campo faltante o inválido, SKU desconocido | Nack sin reencolar: el mensaje va a la DLQ de inmediato. |
| **Transitorio** (la infraestructura es el problema) | Base de datos del Catálogo caída, timeout | Reintento sin límite con espera creciente (1 s, 2 s, 4 s… hasta un tope de ~30 s). El mensaje nunca va a la DLQ por esta causa. |

Mientras un mensaje transitorio se reintenta no se confirma, de modo que la cola queda en pausa
y los eventos siguientes esperan detrás de él, conservando el orden. Una caída prolongada de la
base de datos del Catálogo se comporta igual que el escenario 5: los eventos se acumulan en la
cola y el Catálogo converge cuando el recurso vuelve. Este comportamiento se implementa, pero no
forma parte de los escenarios de prueba ni de la demo.

**Reprocesamiento de la DLQ (no se construye).** En un entorno real, los mensajes de la DLQ se
inspeccionan, se corrige la causa (por ejemplo, un bug del consumidor o un producto no sembrado)
y se devuelven a la cola principal con un script manual. Reprocesar es seguro gracias a la
idempotencia por `eventId` y al descarte de versiones viejas. En este proyecto solo se menciona
en el documento como práctica operativa recomendada.

### 4.4 Consistencia

El Catálogo es **eventualmente consistente**, con un desfase del orden de milisegundos o
segundos. La decisión de venta siempre la toma Inventario, de modo que una consulta
desactualizada en el Catálogo nunca produce una sobreventa.

## 5. Estrategia de pruebas

| Nivel | Herramientas | Qué cubre |
|---|---|---|
| Unitarias | JUnit 5, AssertJ, Mockito | Lógica pura: descuento y rechazo de stock, decisión de aplicar un evento por versión, mapeos. Se escriben con TDD. |
| Integración por servicio | Spring Boot Test, Testcontainers (PostgreSQL, RabbitMQ) | Inventario: venta con JWT (`201`, `401`, `403`, `409`), carrera con dos hilos, escritura en `outbox` en la misma transacción, publicador y reintento. Catálogo: aplicación de eventos, duplicados, orden, DLQ y consulta. |
| Contrato del evento | JSON Schema en `docs/contracts/` | Inventario valida que lo que publica cumple el schema; Catálogo valida que lee el ejemplo. |
| End-to-end | `bash`, `curl`, `jq`, Docker Compose | Un script por escenario en `e2e/` y `run-all.sh` con PASS/FAIL. Los fallos se provocan deteniendo contenedores o publicando mensajes corruptos por la API de RabbitMQ. |

### 5.1 Cobertura por escenario

| # | Unit | Integración | E2E |
|---|:-:|:-:|:-:|
| 1–2 Ventas POS / online | ✓ | ✓ | ✓ |
| 3 Última unidad | | ✓ | ✓ |
| 4 Sin stock | ✓ | ✓ | ✓ |
| 5 Catálogo caído | | | ✓ |
| 6 Duplicado | ✓ | ✓ | |
| 7 Mensaje inválido → DLQ | | ✓ | ✓ |
| 8 RabbitMQ caído (outbox) | | ✓ | ✓ |
| 9 Fuera de orden | ✓ | ✓ | |
| 10 Seguridad | | ✓ | ✓ |
| 11 Latencia | | | ✓ |

No se fija un porcentaje de cobertura. El criterio es que cada escenario tenga al menos una
prueba automatizada que lo demuestre.

### 5.2 Pruebas de escritorio (fase 7)

Checklist manual de los 11 escenarios ejecutado con los scripts E2E, registrando resultado
esperado frente a obtenido y las latencias medidas. La tabla resultante se incluye en el
documento como evidencia.

## 6. Métricas

- **Latencia de propagación:** `updatedAt − occurredAt` por evento. Los contenedores comparten
  el reloj del equipo anfitrión.
- **Medición:** `e2e/measure-latency.sh` ejecuta N ventas (20 por defecto) alternando POS y
  online, calcula mínimo, promedio, p95 y máximo, y escribe `docs/results/latency.md`.
- **Valor esperado:** menos de 1 segundo, dominado por el intervalo del publicador del outbox.
- **Comparación con el "antes":**

| | Antes (lotes cada 30 min) | Después (eventos) |
|---|---|---|
| Peor caso | 30 min | p95 medido |
| Promedio | ~15 min | promedio medido |
| Ventana de sobreventa | Hasta 30 min | Hasta la latencia medida; Inventario rechaza toda venta sin stock |

- **Métricas secundarias:** tiempo de puesta al día del Catálogo tras reiniciarse (escenario 5)
  y tiempo hasta vaciar el outbox tras volver RabbitMQ, con cero eventos perdidos (escenario 8).

## 7. Guion del video

Duración objetivo: unos 8 minutos (máximo 10). Cuatro bloques, uno por integrante; pueden
grabarse por separado. En pantalla: dos terminales (scripts y logs) y la consola de RabbitMQ.

| Tiempo | Bloque | Presenta | Contenido |
|---|---|---|---|
| 0:00–1:30 | Problema y solución | Integrante 1 | Sobreventa por lotes de 30 min, diagrama de arquitectura y patrones elegidos. |
| 1:30–3:30 | Flujo normal | Integrante 2 | `docker compose up`, venta POS y venta online reflejadas en el Catálogo con su latencia, mensaje visible en RabbitMQ. |
| 3:30–5:00 | Consistencia | Integrante 3 | Carrera por la última unidad (`201` frente a `409`); Inventario como fuente de verdad y Catálogo eventualmente consistente. |
| 5:00–7:00 | Tolerancia a fallos | Integrante 4 | Catálogo caído (cola acumulada y recuperación) y RabbitMQ caído (outbox pendiente y publicación sin pérdidas). |
| 7:00–8:00 | Cierre | Integrante 1 o todos | Latencia medida frente a 30 min, cumplimiento del objetivo y escenarios probados no mostrados (4, 6, 7, 9, 10). |

El reparto de bloques entre integrantes lo decide el equipo.

### 7.1 Apoyo para la grabación

- `e2e/reset.sh` restablece el sistema a un estado inicial conocido (stock sembrado, colas y
  outbox vacíos) antes de cada toma.
- Los scripts E2E tienen un modo demo que pausa entre pasos y muestra mensajes legibles en
  español.
- Las métricas se calculan antes de grabar y en el video se muestra el resultado.

## 8. Decisiones descartadas

| Alternativa | Motivo |
|---|---|
| Servicio de Ventas como tercer servicio Spring Boot | Agrega un servicio, una base de datos y un contrato sin demostrar ningún patrón nuevo. |
| Simulador de carga (k6, Gatling) | Herramienta adicional; un script bash cubre la medición necesaria. |
| Modo por lotes reproducible en el Catálogo | El equipo prefirió una medición más simple; el "antes" se explica. |
| Publicación inmediata tras el commit con outbox como respaldo | Optimización innecesaria para la latencia objetivo (YAGNI). |
| Módulo compartido con la clase del evento | Acoplaría los servicios; el contrato se comparte como documento y schema. |
| Script de reprocesamiento de la DLQ | Con la clasificación de errores, la DLQ solo recibe fallos permanentes; no aporta a ningún escenario. Se menciona como práctica para un entorno real. |
| Escenario de prueba "BD del Catálogo caída" | El comportamiento se implementa (sección 4.3), pero no se prueba ni se muestra; su efecto es equivalente al escenario 5. |

## 9. Siguientes fases

- **Fase 2:** contratos (OpenAPI de ambos servicios, JSON Schema del evento, topología de RabbitMQ).
- **Fase 3:** infraestructura con Docker Compose.
- **Fases 4 y 5:** spec, plan e implementación de cada servicio con TDD.
- **Fases 6 a 8:** integración y E2E, pruebas de escritorio, documento y video.

## Referencias

- Erl, T. (2009). *SOA design patterns*. Prentice Hall.
- Nottingham, M., Wilde, E., & Dalal, S. (2023). *Problem details for HTTP APIs* (RFC 9457).
  Internet Engineering Task Force. https://www.rfc-editor.org/rfc/rfc9457
