package com.retail.inventory.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Emisor falso de las pruebas unitarias: registra lo enviado y puede simular un broker caído. */
class FakeStockEventSender implements StockEventSender {

    final List<UUID> sent = new ArrayList<>();
    boolean failing;

    @Override
    public void send(UUID eventId, String eventType, String payload) {
        if (failing) {
            throw new EventPublishException("Connection refused");
        }
        sent.add(eventId);
    }
}
