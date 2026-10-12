-- Stock actual por SKU. Inventario es el único dueño de esta tabla.
CREATE TABLE stock (
    sku        VARCHAR(64)  PRIMARY KEY,
    -- Segunda defensa: la regla de negocio ya impide el stock negativo.
    quantity   INTEGER      NOT NULL CHECK (quantity >= 0),
    -- Versión para el bloqueo optimista (JPA @Version).
    version    BIGINT       NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);
