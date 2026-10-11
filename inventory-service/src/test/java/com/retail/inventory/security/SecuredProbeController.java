package com.retail.inventory.security;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador solo de pruebas con las rutas reales del contrato, mientras no existen sus
 * controladores (llegan en 4.3, 4.4 y 4.6, que lo eliminan). {@link TestComponent} lo excluye del
 * escaneo de {@code @SpringBootTest}; se importa explícitamente.
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

    @GetMapping("/api/v1/inventory/{sku}")
    String getStock(@PathVariable String sku) {
        return "stock";
    }
}
