package com.retail.inventory.error;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Tipos de problema (RFC 9457) del contrato. Es el único lugar donde se definen sus códigos, títulos
 * y estados HTTP.
 */
public enum ProblemType {

    VALIDATION_ERROR("validation-error", "Validation error", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("unauthorized", "Unauthorized", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("forbidden", "Forbidden", HttpStatus.FORBIDDEN),
    NOT_FOUND("not-found", "Not found", HttpStatus.NOT_FOUND),
    INTERNAL_ERROR("internal-error", "Internal error", HttpStatus.INTERNAL_SERVER_ERROR);

    private static final String TYPE_BASE_URI = "https://api.retail-demo.local/problems/";

    private final String code;
    private final String title;
    private final HttpStatus status;

    ProblemType(String code, String title, HttpStatus status) {
        this.code = code;
        this.title = title;
        this.status = status;
    }

    /**
     * Tipo para los errores HTTP estándar de Spring MVC (405, 415, ...), que por defecto no traen
     * {@code type}; el contrato lo exige.
     */
    public static URI genericHttpErrorType() {
        return URI.create(TYPE_BASE_URI + "http-error");
    }

    public HttpStatus status() {
        return status;
    }

    /** Crea el {@link ProblemDetail} de este tipo con el detalle y la ruta que lo originó. */
    public ProblemDetail toProblemDetail(String detail, String requestPath) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE_URI + code));
        problem.setTitle(title);
        problem.setInstance(URI.create(requestPath));
        return problem;
    }
}
