# Guion de la demo (video)

Duración objetivo: unos 8 minutos (máximo 10), cuatro bloques, uno por integrante; pueden grabarse por separado.
Diseño del guion en la spec global [§7](superpowers/specs/2026-10-09-solucion-global-design.md); los resultados que se
muestran están en [`pruebas-de-escritorio.md`](pruebas-de-escritorio.md) y [`results/latency.md`](results/latency.md).

**En pantalla:** una terminal con los scripts, otra con los logs
(`docker compose logs -f catalog-service inventory-service`) y la consola de RabbitMQ
(<http://localhost:15672>, usuario `retail`, contraseña `retail-demo`).

El reparto de los bloques entre los cuatro integrantes lo decide el equipo; abajo se numeran como en la spec.

## Preparación antes de grabar

1. Requisitos: Docker, `curl`, `jq`, bash 5 y GNU `date`.
2. `e2e/selftest.sh` (sin Docker) debe terminar en `Autoprueba OK`.
3. Ensayo completo y medición previa: `e2e/run-all.sh --reset` debe terminar con "11 de 11 escenarios OK". Las métricas se
   calculan **antes** de grabar; en el video solo se muestra el resultado.
4. **Antes de cada toma**, restablece el estado: `e2e/reset.sh` (stock sembrado, colas y outbox vacíos).
5. Cada escenario suelto admite `--demo`: narra cada paso en español, muestra la petición y la respuesta, y **espera Enter**
   entre pasos (así el presentador marca el ritmo). `run-all.sh` no admite `--demo`.

## Bloque 1 · Problema y solución (0:00–1:30) — Integrante 1

Sin comandos; diapositiva o diagrama de arquitectura.

- El problema: las ventas descuentan stock en tiempo real, pero el catálogo digital se actualiza por lotes cada 30 minutos;
  en ese intervalo se muestran como disponibles productos agotados (sobreventas, cancelaciones, reclamos).
- La solución: **Event-Driven Messaging** (Erl, 2009), complementado con **Asynchronous Queuing** y **Reliable Messaging**.
  Inventario (productor) publica el evento "Cambio de Inventario" y el Catálogo (consumidor) mantiene una vista de
  disponibilidad alimentada solo por eventos, con RabbitMQ en medio.
- Cómo se garantiza la entrega: patrón *transactional outbox* en Inventario (el evento se guarda en la misma transacción
  que la venta), publicación con confirmaciones del broker, colas durables, ack manual, DLQ y consumidor idempotente
  (deduplicación por `eventId`).

## Bloque 2 · Flujo normal (1:30–3:30) — Integrante 2

```bash
e2e/reset.sh                       # (antes de la toma) estado inicial
docker compose up -d --wait        # mostrar los servicios sanos
e2e/01-pos-sale.sh --demo          # venta POS reflejada en el Catálogo, con su latencia
e2e/02-online-sale.sh --demo       # venta online
```

- Mostrar en la consola de RabbitMQ el exchange `inventory.events` y la cola `catalog.inventory-stock-changed` (con
  su cola de mensajes rechazados, la DLQ).
- Mostrar la consulta pública del Catálogo: `curl -s http://localhost:8082/api/v1/catalog/products/TENIS-RUNNER-42 | jq`
  con `availableStock`, `updatedAt` y `lastEventLatencyMs`.

## Bloque 3 · Consistencia (3:30–5:00) — Integrante 3

```bash
e2e/reset.sh                       # el escenario 3 necesita BALON-FUTBOL-5 con stock exactamente 1
e2e/03-last-unit-race.sh --demo    # POS y online venden la última unidad a la vez
```

- Una venta obtiene `201` y la otra `409`; se genera un solo evento.
- Idea clave: Inventario es la fuente de verdad y decide cada venta; el Catálogo es eventualmente consistente, así que una
  consulta desactualizada nunca produce una sobreventa.

## Bloque 4 · Tolerancia a fallos (5:00–7:00) — Integrante 4

```bash
e2e/reset.sh
e2e/05-catalog-down.sh --demo      # Catálogo caído: la cola acumula 3 mensajes; al volver, converge en orden
e2e/08-broker-down.sh --demo       # RabbitMQ caído: la venta se confirma, el outbox queda PENDING y luego se publica
```

- En el 5: mostrar en la consola de RabbitMQ la cola acumulando mensajes sin consumidores y luego vaciándose.
- En el 8: mostrar el outbox pendiente (`curl -s "http://localhost:8081/api/v1/inventory/outbox?status=PENDING" | jq`) y
  cómo pasa a `PUBLISHED` al volver el broker; **cero eventos perdidos**.
- Ambos escenarios restauran el contenedor detenido al terminar, incluso si se interrumpen con Ctrl+C.

## Bloque 5 · Cierre (7:00–8:00) — Integrante 1 o todos

```bash
e2e/11-latency.sh --demo           # (o mostrar docs/results/latency.md ya generado)
```

- Latencia medida frente a 30 minutos: p95 de unos 440 ms y promedio de unos 330 ms (valores de las ejecuciones
  documentadas), es decir, miles de veces menor que el peor caso del proceso por lotes; cumple el objetivo de menos de 1 s.
- Mencionar los escenarios probados que no se mostraron: 4 (venta sin stock), 6 (mensaje duplicado), 7 (mensaje inválido
  a la DLQ), 9 (eventos fuera de orden) y 10 (seguridad), todos en verde en la ejecución completa
  (`pruebas-de-escritorio.md`).

## Notas para grabar

- **Orden y reinicios:** el escenario 3 solo corre una vez por reinicio y `TENIS-RUNNER-42` alcanza para unas 5 ventas;
  haz `e2e/reset.sh` entre bloques si grabas por separado. El escenario 11 consume 20 unidades de `GORRA-SPORT-NEGRA`
  (caben 2 ejecuciones por reinicio).
- **Tiempos reales:** los escenarios 5 y 8 tardan unos 10 s cada uno (el reinicio de los servicios); con `--demo` suma el
  tiempo de las pausas.
- **Si algo falla en una toma:** `e2e/reset.sh` y repetir; los escenarios devuelven el sistema a un estado sano aunque se
  interrumpan.

## Checklist del ensayo

- [ ] `e2e/selftest.sh` en verde y `e2e/run-all.sh --reset` con 11 de 11 escenarios OK
- [ ] Terminales y consola de RabbitMQ visibles y legibles en la grabación
- [ ] Bloque 1 ensayado con el diagrama de arquitectura
- [ ] Bloques 2 a 4 ensayados con `--demo` y `e2e/reset.sh` entre ellos
- [ ] Bloque 5: latencia medida lista para mostrar (`docs/results/latency.md`)
- [ ] Duración total entre 5 y 10 minutos
