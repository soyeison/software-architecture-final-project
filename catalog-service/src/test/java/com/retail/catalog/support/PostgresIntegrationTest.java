package com.retail.catalog.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de las pruebas de integración: una sola base PostgreSQL real por ejecución de Maven,
 * compartida por todos los contextos de Spring. El contenedor se detiene solo al terminar la JVM
 * (Ryuk, de Testcontainers).
 */
public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Limita el pool: con muchos contextos de Spring en caché, el valor por defecto agotaría las conexiones de PostgreSQL.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 5);
    }
}
