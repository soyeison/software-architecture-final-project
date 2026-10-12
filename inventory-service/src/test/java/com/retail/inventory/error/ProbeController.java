package com.retail.inventory.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador solo de pruebas para ejercitar el manejo global de errores mientras no existen
 * endpoints reales. {@link TestComponent} lo excluye del escaneo de {@code @SpringBootTest}.
 */
@TestComponent
@RestController
class ProbeController {

    record ProbeRequest(@NotNull @Min(1) Integer quantity) {
    }

    @PostMapping("/probe/echo")
    ProbeRequest echo(@Valid @RequestBody ProbeRequest request) {
        return request;
    }

    @GetMapping("/probe/page")
    int page(@RequestParam int number) {
        return number;
    }

    @GetMapping("/probe/limit")
    int limit(@RequestParam @Min(1) int size) {
        return size;
    }

    @GetMapping("/probe/failure")
    String failure() {
        throw new IllegalStateException("internal detail that must not leak");
    }
}
