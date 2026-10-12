package com.retail.inventory.sale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestStocks;
import com.retail.inventory.support.TestTokens;

/**
 * Concurrencia real contra PostgreSQL: las ventas simultáneas nunca dejan el stock negativo ni
 * pierden descuentos (escenarios 1 a 4, lado Inventario).
 */
@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
@AutoConfigureMockMvc
class SaleConcurrencyIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Resultado de una venta: estado HTTP y, si falló, el tipo de problema (último tramo de {@code type}). */
    record Outcome(int status, String problem) {
    }

    /** Lanza una venta de 1 unidad por hilo, todas a la vez, y devuelve el resultado de cada una. */
    private List<Outcome> sellConcurrently(String sku, int threads) throws Exception {
        String token = "Bearer " + TestTokens.withScope("inventory:sell");
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    MockHttpServletResponse response = mockMvc.perform(post("/api/v1/inventory/" + sku + "/sales")
                                    .header(HttpHeaders.AUTHORIZATION, token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"quantity\": 1, \"channel\": \"POS\"}"))
                            .andReturn().getResponse();
                    String body = response.getContentAsString();
                    String problem = response.getStatus() < 400 ? "" : body.replaceAll(".*problems/([a-z-]+).*", "$1");
                    return new Outcome(response.getStatus(), problem);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private int storedQuantity(String sku) {
        return jdbcTemplate.queryForObject("SELECT quantity FROM stock WHERE sku = ?", Integer.class, sku);
    }

    /** Escenario de la última unidad: exactamente una venta gana y la otra recibe insufficient-stock. */
    @RepeatedTest(20)
    void lastUnitRaceHasExactlyOneWinner() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 1);

        List<Outcome> outcomes = sellConcurrently(sku, 2);

        assertThat(outcomes).containsExactlyInAnyOrder(new Outcome(201, ""), new Outcome(409, "insufficient-stock"));
        assertThat(storedQuantity(sku)).isZero();
    }

    /** Invariante bajo ráfaga: stock final = inicial − ventas exitosas, nunca negativo y sin 5xx. */
    @Test
    void burstKeepsTheStockInvariant() throws Exception {
        int initialStock = 10;
        String sku = TestStocks.insert(jdbcTemplate, initialStock);

        List<Outcome> outcomes = sellConcurrently(sku, 20);

        long successes = outcomes.stream().filter(outcome -> outcome.status() == 201).count();
        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201 || outcome.status() == 409);
        assertThat(successes).isBetween(1L, (long) initialStock);
        assertThat(storedQuantity(sku)).isEqualTo(initialStock - (int) successes).isNotNegative();
        assertOneEventPerSuccessfulSaleInVersionOrder(sku, successes);
    }

    /** Un evento por venta confirmada y, por SKU, el orden de publicación coincide con el de las versiones. */
    private void assertOneEventPerSuccessfulSaleInVersionOrder(String sku, long successes) {
        List<Long> versions = jdbcTemplate.queryForList(
                "SELECT (payload::jsonb ->> 'stockVersion')::bigint FROM outbox WHERE sku = ?"
                        + " ORDER BY sequence_number", Long.class, sku);
        assertThat(versions).hasSize((int) successes).isSorted();
        assertThat(versions).doesNotHaveDuplicates();
    }
}
