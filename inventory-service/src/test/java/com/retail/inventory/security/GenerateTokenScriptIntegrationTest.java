package com.retail.inventory.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.retail.inventory.support.TestTokens;

/**
 * Comprueba que el script real {@code scripts/generate-token.sh} y el validador del servicio están de
 * acuerdo (algoritmo, emisor, claims y permisos de cada perfil).
 */
@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
@AutoConfigureMockMvc
@Import(SecuredProbeController.class)
class GenerateTokenScriptIntegrationTest {

    /** Maven ejecuta las pruebas desde inventory-service/; el script vive en la raíz del repositorio. */
    private static final Path SCRIPT = Path.of("..", "scripts", "generate-token.sh");
    private static final String SALES_PATH = "/api/v1/inventory/TENIS-RUNNER-42/sales";
    private static final String STOCK_PATH = "/api/v1/inventory/TENIS-RUNNER-42";
    private static final String SALE_BODY = "{\"quantity\": 1, \"channel\": \"ONLINE\"}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void posAndOnlineTokensCanSell() throws Exception {
        for (String profile : new String[] {"pos", "online"}) {
            mockMvc.perform(post(SALES_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + generate(profile).output())
                            .contentType(MediaType.APPLICATION_JSON).content(SALE_BODY))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void operatorTokenCanReadButNotSell() throws Exception {
        String token = generate("operator").output();

        mockMvc.perform(get(STOCK_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post(SALES_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(SALE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void noScopeTokenIsValidButForbidden() throws Exception {
        mockMvc.perform(post(SALES_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + generate("no-scope").output())
                        .contentType(MediaType.APPLICATION_JSON).content(SALE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownProfileIsRejectedWithUsage() throws Exception {
        ScriptResult result = generate("admin");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).isEmpty();
    }

    private record ScriptResult(int exitCode, String output) {
    }

    private static ScriptResult generate(String profile) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("bash", SCRIPT.toString(), profile);
        builder.environment().put("JWT_SECRET", TestTokens.SECRET);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("generate-token.sh did not finish");
        }
        return new ScriptResult(process.exitValue(), output);
    }
}
