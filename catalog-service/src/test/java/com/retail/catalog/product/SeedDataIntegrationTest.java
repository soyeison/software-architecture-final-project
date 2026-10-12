package com.retail.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.retail.catalog.support.PostgresIntegrationTest;

/**
 * La semilla de Flyway debe coincidir con {@code docs/seed-data.md}, el documento que también sigue
 * Inventario: así Catálogo e Inventario no se desalinean.
 */
@SpringBootTest
class SeedDataIntegrationTest extends PostgresIntegrationTest {

    record SeededProduct(String name, int price, int stock) {
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seededProductsMatchTheSeedDataDocument() throws IOException {
        Map<String, SeededProduct> documented = readDocumentedProducts();

        Map<String, SeededProduct> seeded = new TreeMap<>();
        jdbcTemplate.query("SELECT sku, name, price, available_stock FROM products WHERE sku NOT LIKE 'TEST-%'",
                resultSet -> {
                    seeded.put(resultSet.getString("sku"), new SeededProduct(resultSet.getString("name"),
                            resultSet.getInt("price"), resultSet.getInt("available_stock")));
                });

        assertThat(documented).hasSize(7);
        assertThat(seeded).isEqualTo(documented);
    }

    @Test
    void seededProductsStartWithoutEventsAndInCop() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT stock_version FROM products WHERE sku NOT LIKE 'TEST-%'", Long.class))
                .isNotEmpty().containsOnly(0L);
        assertThat(jdbcTemplate.queryForList(
                "SELECT last_event_latency_ms FROM products WHERE sku NOT LIKE 'TEST-%' AND last_event_latency_ms IS NOT NULL",
                Long.class)).isEmpty();
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT currency FROM products WHERE sku NOT LIKE 'TEST-%'",
                String.class)).containsExactly("COP");
    }

    /** Lee la tabla del documento: columna 1 = SKU, 2 = producto, 3 = precio (con separador de miles), 4 = stock. */
    private static Map<String, SeededProduct> readDocumentedProducts() throws IOException {
        Map<String, SeededProduct> documented = new TreeMap<>();
        try (InputStream input = new ClassPathResource("seed-data.md").getInputStream()) {
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.startsWith("| `")) {
                    continue;
                }
                String[] cells = line.split("\\|");
                documented.put(cells[1].trim().replace("`", ""),
                        new SeededProduct(cells[2].trim(), Integer.parseInt(cells[3].replaceAll("\\D", "")),
                                Integer.parseInt(cells[4].trim())));
            }
        }
        return documented;
    }
}
