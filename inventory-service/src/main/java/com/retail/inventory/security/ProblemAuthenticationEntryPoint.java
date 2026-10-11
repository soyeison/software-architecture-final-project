package com.retail.inventory.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.retail.inventory.error.ProblemResponseWriter;
import com.retail.inventory.error.ProblemType;

/**
 * Responde 401 {@code unauthorized} cuando falta el token o es inválido (firma, emisor o expiración).
 */
@Component
class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemResponseWriter problemResponseWriter;

    ProblemAuthenticationEntryPoint(ProblemResponseWriter problemResponseWriter) {
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        // RFC 6750: toda respuesta 401 de un recurso protegido por bearer token indica el esquema.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        problemResponseWriter.write(request, response, ProblemType.UNAUTHORIZED, "A valid bearer token is required");
    }
}
