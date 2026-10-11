package com.retail.inventory.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import com.retail.inventory.config.InventoryProperties;

/**
 * Seguridad del servicio (spec de Inventario §5): JWT HS256 sin sesión y reglas por ruta, seguras por
 * defecto (lo que no se declara se deniega).
 */
@Configuration
public class SecurityConfiguration {

    static final String SELL_AUTHORITY = "SCOPE_inventory:sell";
    static final String READ_AUTHORITY = "SCOPE_inventory:read";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemAuthenticationEntryPoint authenticationEntryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // El diagnóstico del outbox va ANTES que la regla de {sku}: si no, exigiría el scope de lectura.
                        .requestMatchers(HttpMethod.GET, "/api/v1/inventory/outbox").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/inventory/*/sales").hasAuthority(SELL_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/inventory/*").hasAuthority(READ_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs/swagger-config", "/openapi/inventory-service.yaml").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Incluye /v3/api-docs: el contrato publicado es solo el escrito a mano.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /** Solo HS256 con el secreto de {@code JWT_SECRET}; valida emisor y expiración. */
    @Bean
    JwtDecoder jwtDecoder(InventoryProperties properties) {
        SecretKeySpec key = new SecretKeySpec(
                properties.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // La expiración es obligatoria: por defecto Spring acepta tokens sin claim exp, y un token así no caducaría nunca.
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
                new JwtIssuerValidator(properties.jwt().issuer()), timestamps));
        return decoder;
    }
}
