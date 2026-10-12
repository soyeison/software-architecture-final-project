package com.retail.inventory.security;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador solo de pruebas con las rutas del contrato que aún no tienen controlador real: la de
 * venta llega en 4.4 y la del outbox en 4.6, que las eliminan (la de consulta de stock es real desde
 * 4.3). {@link TestComponent} lo excluye del escaneo de {@code @SpringBootTest}; se importa
 * explícitamente.
 */
@TestComponent
@RestController
class SecuredProbeController {

    @PostMapping("/api/v1/inventory/{sku}/sales")
    @ResponseStatus(HttpStatus.CREATED)
    String registerSale(@PathVariable String sku) {
        return "sale-accepted";
    }

    @GetMapping("/api/v1/inventory/outbox")
    String listOutboxEvents() {
        return "outbox";
    }
}
