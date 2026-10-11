package com.retail.inventory.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.retail.inventory.error.ProblemResponseWriter;
import com.retail.inventory.error.ProblemType;

/**
 * Responde 403 {@code forbidden} cuando el token es válido pero no tiene el permiso requerido.
 */
@Component
class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter problemResponseWriter;

    ProblemAccessDeniedHandler(ProblemResponseWriter problemResponseWriter) {
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        problemResponseWriter.write(request, response, ProblemType.FORBIDDEN, "The token lacks the required scope");
    }
}
