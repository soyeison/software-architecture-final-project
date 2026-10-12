package com.retail.inventory.security;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador solo de pruebas con la ruta del contrato que aún no tiene controlador real: la del
 * outbox llega en 4.6, que lo elimina (la de consulta de stock es real desde 4.3 y la de venta desde
 * 4.4). {@link TestComponent} lo excluye del escaneo de {@code @SpringBootTest}; se importa
 * explícitamente.
 */
@TestComponent
@RestController
class SecuredProbeController {

    @GetMapping("/api/v1/inventory/outbox")
    String listOutboxEvents() {
        return "outbox";
    }
}
