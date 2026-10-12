-- Outbox transaccional: el evento se escribe en la misma transacción que el cambio de stock y un
-- publicador interno lo envía después al broker (patrón Transactional Outbox).
CREATE TABLE outbox (
    -- Fija el orden de publicación.
    sequence_number BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        UUID         NOT NULL UNIQUE,
    sku             VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    -- El JSON del evento, ya serializado.
    payload         TEXT         NOT NULL,
    -- PENDING | PUBLISHED
    status          VARCHAR(16)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      TEXT,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ
);

-- El publicador solo lee los pendientes, en orden.
CREATE INDEX outbox_pending_index ON outbox (sequence_number) WHERE status = 'PENDING';
