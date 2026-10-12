package com.retail.catalog.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class ProblemTypeTest {

    @Test
    void instanceKeepsAPlainPathAsIs() {
        ProblemDetail problem = ProblemType.NOT_FOUND.toProblemDetail("missing", "/api/v1/catalog/products/A");

        assertThat(problem.getInstance()).hasToString("/api/v1/catalog/products/A");
    }

    @Test
    void aPathWithCharactersThatAreNotValidInAUriDoesNotMakeTheErrorHandlerFail() {
        ProblemDetail problem = ProblemType.NOT_FOUND.toProblemDetail("missing", "/api/v1/catalog/products/a[b]");

        assertThat(problem.getInstance()).hasToString("/api/v1/catalog/products/a%5Bb%5D");
    }

    @Test
    void aPathWithBracesIsEncodedInsteadOfBeingTreatedAsATemplateVariable() {
        ProblemDetail problem = ProblemType.NOT_FOUND.toProblemDetail("missing", "/api/v1/catalog/products/a{b}");

        assertThat(problem.getInstance()).hasToString("/api/v1/catalog/products/a%7Bb%7D");
    }
}
