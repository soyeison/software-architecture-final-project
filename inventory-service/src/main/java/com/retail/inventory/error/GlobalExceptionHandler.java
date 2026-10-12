package com.retail.inventory.error;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.retail.inventory.sale.StockConflictException;
import com.retail.inventory.stock.InsufficientStockException;
import com.retail.inventory.stock.SkuNotFoundException;

/**
 * Traduce excepciones a respuestas {@code application/problem+json} (RFC 9457). Las excepciones
 * estándar de Spring MVC conservan su estado HTTP (405, 415, ...) gracias a
 * {@link ResponseEntityExceptionHandler}; las del contrato usan {@link ProblemType}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI ABOUT_BLANK = URI.create("about:blank");
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> fieldViolations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), messageOrDefault(error.getDefaultMessage())))
                .toList();
        // Violaciones de clase (no de un campo): se reportan con el nombre del objeto como campo.
        List<FieldViolation> objectViolations = exception.getBindingResult().getGlobalErrors().stream()
                .map(error -> new FieldViolation(error.getObjectName(), messageOrDefault(error.getDefaultMessage())))
                .toList();
        List<FieldViolation> violations = Stream.concat(fieldViolations.stream(), objectViolations.stream()).toList();
        return validationProblem("The request has invalid fields", violations, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validationProblem("The request body is malformed or has invalid values", List.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        return validationProblem("The request is missing a required parameter",
                List.of(new FieldViolation(exception.getParameterName(), "is required")), request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return validationProblem("The request has a parameter with an invalid value",
                List.of(new FieldViolation(exception.getPropertyName(), "has an invalid value")), request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        List<FieldViolation> violations = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(result.getMethodParameter().getParameterName(),
                                messageOrDefault(error.getDefaultMessage()))))
                .toList();
        return validationProblem("The request has invalid parameters", violations, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemType.NOT_FOUND
                .toProblemDetail("The requested resource does not exist", requestPath(request));
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(SkuNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleSkuNotFound(SkuNotFoundException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemType.NOT_FOUND.toProblemDetail(exception.getMessage(), request.getRequestURI());
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientStock(InsufficientStockException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemType.INSUFFICIENT_STOCK
                .toProblemDetail(exception.getMessage(), request.getRequestURI());
        problem.setProperty("availableStock", exception.getAvailableStock());
        problem.setProperty("requestedQuantity", exception.getRequestedQuantity());
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(StockConflictException.class)
    public ResponseEntity<ProblemDetail> handleStockConflict(StockConflictException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemType.CONCURRENT_MODIFICATION
                .toProblemDetail("Stock changed concurrently, please retry", request.getRequestURI());
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

    private static ResponseEntity<Object> validationProblem(String detail, List<FieldViolation> violations,
            WebRequest request) {
        ProblemDetail problem = ProblemType.VALIDATION_ERROR.toProblemDetail(detail, requestPath(request));
        problem.setProperty("errors", violations);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** El schema exige {@code message}: si la restricción no trae texto, se usa uno genérico. */
    private static String messageOrDefault(String message) {
        return message != null ? message : "is invalid";
    }

    private static String requestPath(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }
}
