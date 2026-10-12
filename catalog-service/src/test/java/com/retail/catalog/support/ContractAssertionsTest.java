package com.retail.catalog.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContractAssertionsTest {

    @Test
    void acceptsBodyThatMatchesSchema() {
        String problem = """
                {"type":"https://api.retail-demo.local/problems/not-found","title":"Not found",
                 "status":404,"detail":"missing","instance":"/api/v1/catalog/products/UNKNOWN"}
                """;

        assertThatCode(() -> ContractAssertions.assertMatchesSchema(problem, "Problem")).doesNotThrowAnyException();
    }

    @Test
    void rejectsBodyThatViolatesSchema() {
        String problemWithTextStatus = """
                {"type":"https://api.retail-demo.local/problems/not-found","title":"Not found","status":"404"}
                """;

        assertThatThrownBy(() -> ContractAssertions.assertMatchesSchema(problemWithTextStatus, "Problem"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Problem");
    }

    @Test
    void acceptsAProductAndRejectsOneWithANegativeStock() {
        String product = """
                {"sku":"TENIS-RUNNER-42","name":"Tenis Runner talla 42","description":"Tenis para correr",
                 "price":289900,"currency":"COP","availableStock":4,"inStock":true,
                 "updatedAt":"2026-10-10T15:04:05.512Z","lastEventLatencyMs":389}
                """;
        String negativeStock = product.replace("\"availableStock\":4", "\"availableStock\":-1");

        assertThatCode(() -> ContractAssertions.assertMatchesSchema(product, "Product")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ContractAssertions.assertMatchesSchema(negativeStock, "Product"))
                .isInstanceOf(AssertionError.class);
    }
}
