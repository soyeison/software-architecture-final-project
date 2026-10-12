package com.retail.inventory.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Crea registros de stock con SKU propios (<code>TEST-&lt;UUID en mayúsculas&gt;</code>) para que las pruebas que
 * modifican stock no toquen la semilla. Se inserta con {@code version = 1}, igual que la semilla.
 */
public final class TestStocks {

    private TestStocks() {
    }

    /** Inserta un SKU nuevo con la cantidad indicada y devuelve su código. */
    public static String insert(JdbcTemplate jdbcTemplate, int quantity) {
        String sku = "TEST-" + UUID.randomUUID().toString().toUpperCase();
        jdbcTemplate.update("INSERT INTO stock (sku, quantity, version, updated_at) VALUES (?, ?, 1, ?)",
                sku, quantity, Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MILLIS)));
        return sku;
    }
}
