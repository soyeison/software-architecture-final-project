package com.retail.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestTokens;

/**
 * La semilla de Flyway debe coincidir con {@code docs/seed-data.md}, el documento que también sigue
 * el Catálogo: así Inventario y Catálogo no se desalinean.
 */
@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
class SeedDataIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seededStockMatchesTheSeedDataDocument() throws IOException {
        Map<String, Integer> documented = readDocumentedStock();

        Map<String, Integer> seeded = new TreeMap<>();
        jdbcTemplate.query("SELECT sku, quantity FROM stock WHERE sku NOT LIKE 'TEST-%'",
                resultSet -> {
                    seeded.put(resultSet.getString("sku"), resultSet.getInt("quantity"));
                });

        assertThat(documented).hasSize(7);
        assertThat(seeded).isEqualTo(documented);
    }

    @Test
    void everySeededSkuStartsAtVersionOne() {
        List<Long> versions = jdbcTemplate.queryForList(
                "SELECT version FROM stock WHERE sku NOT LIKE 'TEST-%'", Long.class);

        assertThat(versions).isNotEmpty().containsOnly(1L);
    }

    /** Lee la tabla de SKU del documento: columna 1 = SKU, columna 4 = stock inicial. */
    private static Map<String, Integer> readDocumentedStock() throws IOException {
        Map<String, Integer> documented = new TreeMap<>();
        try (InputStream input = new ClassPathResource("seed-data.md").getInputStream()) {
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.startsWith("| `")) {
                    continue;
                }
                String[] cells = line.split("\\|");
                documented.put(cells[1].trim().replace("`", ""), Integer.parseInt(cells[4].trim()));
            }
        }
        return documented;
    }
}
