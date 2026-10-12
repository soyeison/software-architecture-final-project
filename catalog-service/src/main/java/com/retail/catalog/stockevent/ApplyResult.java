package com.retail.catalog.stockevent;

/** Qué hizo {@link ApplyStockChangeService} con un evento. Todos los resultados se confirman (ack). */
public enum ApplyResult {
    /** El evento era nuevo y más reciente: se aplicó el stock. */
    APPLIED,
    /** El {@code eventId} ya se había procesado: no se hizo nada. */
    DUPLICATE,
    /** El evento es viejo o llegó fuera de orden ({@code stockVersion} no mayor que la guardada): no cambió el stock. */
    STALE
}
