-- Eventos de Inventario ya procesados: base de la idempotencia del consumidor (deduplicación por eventId).
-- Se escribe en la misma transacción que aplica el evento; la clave primaria es la red de seguridad
-- frente a duplicados simultáneos.
CREATE TABLE processed_events (
    event_id     UUID         PRIMARY KEY,
    processed_at TIMESTAMPTZ  NOT NULL
);
