package com.retail.inventory.error;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Escribe una respuesta {@code application/problem+json} directamente sobre la respuesta HTTP. Lo usan
 * los componentes que responden antes de llegar a los controladores (los filtros de seguridad), con
 * los mismos {@link ProblemType} que el {@link GlobalExceptionHandler}.
 *
 * <p>Usa el {@link JsonMapper} de Spring Boot, que registra la mezcla de Jackson para
 * {@code ProblemDetail}; sin ella el cuerpo incluiría un campo {@code properties} ajeno al contrato.
 */
@Component
public class ProblemResponseWriter {

    private final JsonMapper jsonMapper;

    public ProblemResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ProblemType problemType,
            String detail) throws IOException {
        ProblemDetail problem = problemType.toProblemDetail(detail, request.getRequestURI());
        response.setStatus(problemType.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
