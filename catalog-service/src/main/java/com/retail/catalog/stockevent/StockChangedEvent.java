package com.retail.catalog.stockevent;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Evento {@code InventoryStockChanged} tal como lo lee el Catálogo. <b>Consumidor tolerante:</b> ignora
 * las propiedades que no necesita ({@code eventType}, campos nuevos del productor). Solo se valida lo
 * que el Catálogo usa; {@code channel} es informativo (para el log) y no se valida.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StockChangedEvent(
        @NotNull UUID eventId,
        @NotBlank @Size(max = 64) String sku,
        @NotNull @Min(0) Integer newStock,
        @NotNull @Min(1) Long stockVersion,
        String channel,
        @NotNull Instant occurredAt) {
}
