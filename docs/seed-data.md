# Datos semilla

Conjunto de productos que siembran los dos servicios al arrancar (migración Flyway) y que usan los
scripts E2E. La infraestructura entrega bases vacías; cada servicio siembra la suya con **el mismo
conjunto de SKU**. Diseño en la
[spec de infraestructura](superpowers/specs/2026-10-10-infraestructura-design.md).

| SKU | Producto | Precio (COP) | Stock inicial | Escenario que lo usa |
|---|---|---|---|---|
| `TENIS-RUNNER-42` | Tenis Runner talla 42 | 289 900 | 5 | Flujo normal (POS y online) |
| `BALON-FUTBOL-5` | Balón de fútbol n.º 5 | 119 900 | 1 | Última unidad (carrera POS vs. online) |
| `CAMISETA-DRYFIT-M` | Camiseta Dry-Fit talla M | 79 900 | 0 | Venta sin stock |
| `MOCHILA-TRAIL-20L` | Mochila Trail 20 L | 189 900 | 10 | Catálogo caído (varias ventas acumuladas) |
| `BOTELLA-TERMICA-750` | Botella térmica 750 ml | 59 900 | 10 | RabbitMQ caído (outbox) |
| `GORRA-SPORT-NEGRA` | Gorra Sport negra | 39 900 | 50 | Medición de latencia (20 ventas, con margen) |

## Reglas

- Cada escenario usa **su propio SKU**, de modo que no se interfieren y pueden ensayarse en
  cualquier orden. Para volver al estado inicial basta `docker compose down` y `docker compose up -d --wait`.
- **Inventario** siembra `version = 1` con el stock de la tabla.
- **Catálogo** siembra la misma disponibilidad, su versión interna del stock en `0` (de modo que
  cualquier primer evento, con `stockVersion >= 1`, se aplique), `updatedAt` igual a la hora del
  sembrado y `lastEventLatencyMs = null`.
- Los SKU cumplen el patrón del contrato (`^[A-Z0-9][A-Z0-9-]*$`). Los valores de los ejemplos de
  los OpenAPI son ilustrativos y no necesitan coincidir con este sembrado.
- `currency` de todos los productos: `COP`.
