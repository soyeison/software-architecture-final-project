# Latencia de propagación venta → Catálogo

- **Fecha (UTC):** 2026-10-12T21:26:42Z
- **Muestras:** 20 ventas de `GORRA-SPORT-NEGRA`, alternando POS y online, una tras otra
- **Definición:** `lastEventLatencyMs` del Catálogo = `updatedAt − occurredAt` (momento en que el
  Catálogo aplica el evento menos el momento de la venta); los contenedores comparten el reloj del anfitrión
- **Objetivo:** p95 por debajo de 1000 ms; **cumple: sí**

| Mínimo | Promedio | p95 | Máximo |
|---|---|---|---|
| 265 ms | 384.6 ms | 466 ms | 508 ms |

## Comparación con el proceso por lotes ("antes")

| | Antes (lotes cada 30 min) | Después (eventos) |
|---|---|---|
| Peor caso | 30 min (1800000 ms) | p95 medido: 466 ms |
| Promedio | ~15 min (900000 ms) | promedio medido: 384.6 ms |
| Ventana de sobreventa | Hasta 30 min | Hasta la latencia medida; Inventario rechaza toda venta sin stock |

El peor caso medido (p95) es unas **3862 veces** menor que el peor caso del proceso por lotes.

## Muestras

| # | Canal | Latencia (ms) |
|---|---|---|
| 1 | POS | 508 |
| 2 | ONLINE | 318 |
| 3 | POS | 341 |
| 4 | ONLINE | 355 |
| 5 | POS | 380 |
| 6 | ONLINE | 399 |
| 7 | POS | 416 |
| 8 | ONLINE | 447 |
| 9 | POS | 265 |
| 10 | ONLINE | 294 |
| 11 | POS | 320 |
| 12 | ONLINE | 336 |
| 13 | POS | 362 |
| 14 | ONLINE | 388 |
| 15 | POS | 413 |
| 16 | ONLINE | 444 |
| 17 | POS | 400 |
| 18 | ONLINE | 433 |
| 19 | POS | 466 |
| 20 | ONLINE | 408 |
