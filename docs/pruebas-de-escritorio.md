# Pruebas de escritorio

Registro de la ejecución de los 11 escenarios de la solución (spec global
[§4.2](superpowers/specs/2026-10-09-solucion-global-design.md)), con el resultado esperado frente al
obtenido y las latencias medidas. Es la evidencia de la fase 7 y se incluye en el documento final.

## Entorno y ejecución

| | |
|---|---|
| Fecha de la ejecución | 2026-10-12 (23:39 UTC) |
| Versión del código | `main`, commit `50cdcf9` |
| Comando | `e2e/run-all.sh --reset --report resultados.md` (reinicia el stack y ejecuta los 11 escenarios en secuencia) |
| Resultado global | **11 de 11 escenarios OK** |
| Infraestructura | Docker Compose: RabbitMQ 4.3, PostgreSQL 17 (una base por servicio), `inventory-service` y `catalog-service` (Java 25, Spring Boot 4.1.1) |
| Reloj | Los contenedores comparten el reloj del equipo anfitrión, así que `updatedAt − occurredAt` es una medida válida |

Cada escenario usa su propio SKU de [`seed-data.md`](seed-data.md) y compara contra el estado real de las APIs.
Los scripts están en `e2e/` y esperan por condición (nunca con pausas fijas).

## Escenarios: esperado frente a obtenido

| # | Escenario (script) | Resultado esperado | Resultado obtenido | Estado |
|---|---|---|---|---|
| 1 | Venta POS (`01-pos-sale.sh`) | La venta responde `201`; el Catálogo refleja el nuevo stock con su latencia; el evento queda publicado | `201`; stock de `TENIS-RUNNER-42` 5 → 4 en Inventario; el Catálogo mostró 4 a los 866 ms; latencia 781 ms; evento `PUBLISHED` en el outbox y cola vacía | ✅ |
| 2 | Venta online (`02-online-sale.sh`) | Igual que el 1 con `channel = ONLINE` | `201`; stock 4 → 3; Catálogo al día a los 655 ms; latencia 495 ms; evento `PUBLISHED` y cola vacía | ✅ |
| 3 | Carrera por la última unidad (`03-last-unit-race.sh`) | Con stock 1, POS y online venden a la vez: una `201` y la otra `409`; un solo evento | `201` y `409` por stock insuficiente en `BALON-FUTBOL-5`; stock 0; **un solo** evento nuevo en el outbox; el Catálogo mostró stock 0 y `inStock = false` | ✅ |
| 4 | Venta sin stock (`04-out-of-stock.sh`) | `409`, sin cambios de stock y sin evento | `409` por stock insuficiente en `CAMISETA-DRYFIT-M`; stock sigue en 0; cero eventos nuevos; el Catálogo sigue en 0 | ✅ |
| 5 | Catálogo caído (`05-catalog-down.sh`) | Los eventos se acumulan en la cola durable; al reiniciar se procesan y el stock converge | Con el Catálogo detenido: 3 ventas `201` en `MOCHILA-TRAIL-20L`, la cola acumuló los 3 mensajes con 0 consumidores; al reiniciar el stock convergió de 10 a 7, **aplicado en el orden de las ventas**, cola vacía. Puesta al día: **6528 ms** (incluye el arranque del servicio) | ✅ |
| 6 | Mensaje duplicado (`06-duplicate-message.sh`) | El `eventId` ya registrado se reconoce: ack sin cambios | Un evento inyectado se aplicó una vez (stock 15); su reenvío y un segundo reenvío con el mismo `eventId`, otra versión y otro stock se reconocieron como duplicados (descartados por `eventId`, no por versión); la DLQ no recibió nada | ✅ |
| 7 | Mensaje inválido (`07-invalid-message.sh`) | Nack sin reencolar, el mensaje termina en la DLQ sin bloquear la cola | JSON corrupto, evento sin `newStock` y evento de SKU desconocido: los 3 terminaron en la DLQ (+3), la cola principal quedó en 0 y una venta real posterior sí llegó al Catálogo (la cola no se bloqueó) | ✅ |
| 8 | RabbitMQ caído (`08-broker-down.sh`) | La venta se confirma y el evento queda pendiente en el outbox; al volver RabbitMQ se publica; cero eventos perdidos | Con RabbitMQ detenido: 2 ventas `201` en `BOTELLA-TERMICA-750`, 2 eventos `PENDING`, Catálogo sano y sin cambios. Al volver: ambos `PUBLISHED`, stock del Catálogo 10 → 8, ningún `PENDING`, aplicados en orden. Outbox vaciado en **5857 ms** y Catálogo al día en **5869 ms** tras reiniciar RabbitMQ | ✅ |
| 9 | Eventos fuera de orden (`09-out-of-order.sh`) | Un evento con `stockVersion` menor o igual al guardado se ignora con ack; el estado final es correcto | Se inyectó la versión V+2 (stock 12, aplicada) y después la V+1 (stock 14): se descartó como `event_stale`; el stock final fue 12; la DLQ no recibió nada | ✅ |
| 10 | Seguridad (`10-security.sh`) | Sin token o con token inválido, `401`; con token sin permiso de venta, `403` | Sin token `401`; token inválido `401`; token sin permiso `403`; token de solo lectura `403`; el stock no cambió; la consulta del Catálogo sin token responde `200` | ✅ |
| 11 | Latencia (`11-latency.sh`) | `updatedAt − occurredAt` visible; p95 por debajo de 1 s | 20 ventas alternando POS y online: mínimo 25 ms, promedio 322,2 ms, **p95 435 ms**, máximo 443 ms | ✅ |

## Latencia frente al proceso por lotes

El informe generado por el escenario 11 está en [`results/latency.md`](results/latency.md) (otra ejecución del mismo
escenario: p95 441 ms, promedio 331,1 ms). Las cifras varían entre ejecuciones porque las ventas se hacen en serie y
quedan desfasadas respecto al sondeo de 500 ms del publicador del outbox (en las ejecuciones realizadas, el p95 estuvo
entre 435 y 466 ms y el mínimo entre 25 y 265 ms); lo estable es que **todas están muy por debajo de 1 s**.

| | Antes (lotes cada 30 min) | Después (eventos) |
|---|---|---|
| Peor caso | 30 min | p95 medido: ~440 ms |
| Promedio | ~15 min | promedio medido: ~330 ms |
| Ventana de sobreventa | Hasta 30 min | Hasta la latencia medida; Inventario rechaza toda venta sin stock |

La decisión de venta siempre la toma Inventario, de modo que una consulta desactualizada del Catálogo nunca produce
una sobreventa.

## Métricas de recuperación (escenarios 5 y 8)

| Métrica | Valor | Qué mide |
|---|---|---|
| `catalog_catch_up_ms` | 6528 ms | Desde que se reinicia el Catálogo hasta que su stock converge (incluye el arranque del servicio) |
| `outbox_drain_ms` | 5857 ms | Desde que se reinicia RabbitMQ hasta que el outbox publica los eventos pendientes |
| `broker_recovery_catch_up_ms` | 5869 ms | Desde que se reinicia RabbitMQ hasta que el Catálogo converge |

## Cómo repetir las pruebas

```bash
docker compose up -d --wait         # el stack debe estar levantado (requiere Docker, curl y jq)
e2e/run-all.sh --reset --report resultados.md   # los 11 escenarios; --reset reinicia el stack (borra sus datos)
e2e/03-last-unit-race.sh            # un escenario suelto (--demo narra paso a paso)
e2e/selftest.sh                     # prueba de la lógica de los scripts, sin Docker
```

Notas: el stock sembrado se agota (el escenario 3 solo corre una vez por reinicio y el stock de `TENIS-RUNNER-42`
alcanza para unas 5 ventas), por lo que para repetir hay que reiniciar con `e2e/reset.sh` o `--reset`. Los escenarios 5
y 8 detienen contenedores y los restauran al terminar, incluso si se interrumpen con Ctrl+C. Detalle de los scripts
en la [spec E2E](superpowers/specs/2026-10-12-e2e-design.md).
