# Latencia de propagación venta → Catálogo

- **Fecha (UTC):** 2026-10-12T23:36:19Z
- **Muestras:** 20 ventas de `GORRA-SPORT-NEGRA`, alternando POS y online, una tras otra
- **Definición:** `lastEventLatencyMs` del Catálogo = `updatedAt − occurredAt` (momento en que el
  Catálogo aplica el evento menos el momento de la venta); los contenedores comparten el reloj del anfitrión
- **Objetivo:** p95 por debajo de 1000 ms; **cumple: sí**

| Mínimo | Promedio | p95 | Máximo |
|---|---|---|---|
| 68 ms | 331.1 ms | 441 ms | 441 ms |

## Comparación con el proceso por lotes ("antes")

| | Antes (lotes cada 30 min) | Después (eventos) |
|---|---|---|
| Peor caso | 30 min (1800000 ms) | p95 medido: 441 ms |
| Promedio | ~15 min (900000 ms) | promedio medido: 331.1 ms |
| Ventana de sobreventa | Hasta 30 min | Hasta la latencia medida; Inventario rechaza toda venta sin stock |

El p95 medido es unas **4081 veces** menor que el peor caso del proceso por lotes (30 min).

La latencia está dominada por el intervalo del publicador del outbox (`fixedDelay` de 500 ms): al registrar
las ventas en serie, el desfase respecto al sondeo forma un diente de sierra, y el mínimo varía entre
ejecuciones (por ejemplo 96 ms en una y 265 ms en otra). Ver la spec global §6.

## Muestras

| # | Canal | Latencia (ms) |
|---|---|---|
| 1 | POS | 68 |
| 2 | ONLINE | 300 |
| 3 | POS | 323 |
| 4 | ONLINE | 352 |
| 5 | POS | 377 |
| 6 | ONLINE | 401 |
| 7 | POS | 411 |
| 8 | ONLINE | 441 |
| 9 | POS | 255 |
| 10 | ONLINE | 284 |
| 11 | POS | 308 |
| 12 | ONLINE | 333 |
| 13 | POS | 358 |
| 14 | ONLINE | 382 |
| 15 | POS | 411 |
| 16 | ONLINE | 441 |
| 17 | POS | 257 |
| 18 | ONLINE | 280 |
| 19 | POS | 307 |
| 20 | ONLINE | 333 |
