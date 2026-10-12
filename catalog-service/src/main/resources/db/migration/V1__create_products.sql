-- Productos del catálogo y su vista de disponibilidad. El Catálogo es el único dueño de esta tabla;
-- la disponibilidad solo cambia al aplicar eventos de Inventario.
CREATE TABLE products (
    sku                   VARCHAR(64)  PRIMARY KEY,
    name                  VARCHAR(120) NOT NULL,
    description           TEXT         NOT NULL,
    -- Precio en pesos colombianos, sin decimales.
    price                 INTEGER      NOT NULL CHECK (price >= 0),
    currency              VARCHAR(3)   NOT NULL,
    available_stock       INTEGER      NOT NULL CHECK (available_stock >= 0),
    -- Versión interna del stock (la del último evento aplicado); la semilla usa 0.
    stock_version         BIGINT       NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    -- Latencia del último evento aplicado; nulo si nunca recibió un evento.
    last_event_latency_ms BIGINT
);
