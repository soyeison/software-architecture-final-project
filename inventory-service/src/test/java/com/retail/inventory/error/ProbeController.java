package com.retail.inventory.error;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.retail.inventory.sale.StockConflictException;

/**
 * Controlador solo de pruebas para ejercitar el manejo global de errores mientras no existen
 * endpoints reales. {@link TestComponent} lo excluye del escaneo de {@code @SpringBootTest}.
 */
@TestComponent
@RestController
class ProbeController {

    /** Restricción de clase (no de campo), para probar las violaciones a nivel de objeto. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = RangeValidator.class)
    @interface ValidRange {

        String message() default "from must not be greater than to";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    static class RangeValidator implements ConstraintValidator<ValidRange, RangeRequest> {

        @Override
        public boolean isValid(RangeRequest request, ConstraintValidatorContext context) {
            return request.from() <= request.to();
        }
    }

    @ValidRange
    record RangeRequest(int from, int to) {
    }

    record ProbeRequest(@NotNull @Min(1) Integer quantity) {
    }

    @PostMapping("/probe/echo")
    ProbeRequest echo(@Valid @RequestBody ProbeRequest request) {
        return request;
    }

    @PostMapping("/probe/range")
    RangeRequest range(@Valid @RequestBody RangeRequest request) {
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

    @GetMapping("/probe/conflict")
    String conflict() {
        throw new StockConflictException("TENIS-RUNNER-42", 5, new IllegalStateException("version conflict"));
    }

    @GetMapping("/probe/failure")
    String failure() {
        throw new IllegalStateException("internal detail that must not leak");
    }
}
