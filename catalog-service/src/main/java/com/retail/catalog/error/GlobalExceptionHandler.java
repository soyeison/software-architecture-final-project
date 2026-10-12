package com.retail.catalog.error;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduce excepciones a respuestas {@code application/problem+json} (RFC 9457). Las excepciones
 * estándar de Spring MVC conservan su estado HTTP (405, 415, ...) gracias a
 * {@link ResponseEntityExceptionHandler}; las del contrato usan {@link ProblemType}. El Catálogo no
 * recibe cuerpos ni parámetros de consulta, así que no tiene errores de validación propios.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI ABOUT_BLANK = URI.create("about:blank");
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemType.NOT_FOUND
                .toProblemDetail("The requested resource does not exist", requestPath(request));
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** Da el tipo genérico {@code http-error} a los problemas estándar de Spring que no traen {@code type}. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        // Spring MVC pasa body nulo y arma el problema después, a partir de la excepción.
        ProblemDetail problem = body instanceof ProblemDetail existing ? existing
                : exception instanceof ErrorResponse errorResponse ? errorResponse.getBody() : null;
        if (problem != null && (problem.getType() == null || ABOUT_BLANK.equals(problem.getType()))) {
            problem.setType(ProblemType.genericHttpErrorType());
        }
        return super.handleExceptionInternal(exception, body, headers, statusCode, request);
    }

    /** Último recurso: nunca expone detalles internos, pero registra la excepción completa. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("unexpected_error path={}", request.getRequestURI(), exception);
        ProblemDetail problem = ProblemType.INTERNAL_ERROR
                .toProblemDetail("An unexpected error occurred", request.getRequestURI());
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static String requestPath(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }
}
