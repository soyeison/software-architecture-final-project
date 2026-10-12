package com.retail.inventory.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContractAssertionsTest {

    @Test
    void acceptsBodyThatMatchesSchema() {
        String problem = """
                {"type":"https://api.retail-demo.local/problems/not-found","title":"Not found",
                 "status":404,"detail":"missing","instance":"/api/v1/inventory/UNKNOWN"}
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
    void rejectsValidationProblemWithoutErrors() {
        String validationProblemWithoutErrors = """
                {"type":"https://api.retail-demo.local/problems/validation-error","title":"Validation error",
                 "status":400}
                """;

        assertThatThrownBy(() -> ContractAssertions.assertMatchesSchema(validationProblemWithoutErrors,
                "ValidationProblem")).isInstanceOf(AssertionError.class);
    }

    @Test
    void acceptsTheContractExampleOfTheEvent() throws Exception {
        String valid = new String(getClass().getResourceAsStream("/contracts/events/examples/valid.json")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertThatCode(() -> ContractAssertions.assertMatchesEventSchema(valid)).doesNotThrowAnyException();
    }

    @Test
    void rejectsEventWithoutMilliseconds() throws Exception {
        String invalid = new String(getClass()
                .getResourceAsStream("/contracts/events/examples/invalid-missing-millis.json").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ContractAssertions.assertMatchesEventSchema(invalid))
                .isInstanceOf(AssertionError.class);
    }
}
