package com.retail.inventory.outbox;

import com.retail.inventory.stock.Channel;

/**
 * Cuerpo del evento {@code InventoryStockChanged} (schema {@code inventory-stock-changed.schema.json}).
 * {@code occurredAt} ya viene formateado: el contrato exige exactamente 3 decimales.
 */
record StockChangedEvent(String eventId, String eventType, String sku, int newStock, long stockVersion,
        Channel channel, String occurredAt) {
}
