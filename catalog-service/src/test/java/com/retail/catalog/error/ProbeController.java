package com.retail.catalog.error;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador solo de pruebas para ejercitar el manejo global de errores mientras no existen
 * endpoints reales (llegan en 5.2). {@link TestComponent} lo excluye del escaneo de
 * {@code @SpringBootTest}.
 */
@TestComponent
@RestController
class ProbeController {

    @GetMapping("/probe/failure")
    String failure() {
        throw new IllegalStateException("internal detail that must not leak");
    }
}
