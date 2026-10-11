package com.retail.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class InventoryPropertiesTest {

    private static final String SECRET_OF_32_BYTES = "0123456789abcdef0123456789abcdef";

    @Configuration
    @EnableConfigurationProperties(InventoryProperties.class)
    static class PropertiesConfiguration {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("inventory.jwt.issuer=retail-demo-auth");

    @Test
    void startsWithSecretOfAtLeast32Bytes() {
        contextRunner.withPropertyValues("inventory.jwt.secret=" + SECRET_OF_32_BYTES)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(InventoryProperties.class).jwt().issuer()).isEqualTo("retail-demo-auth");
                });
    }

    @Test
    void failsToStartWhenSecretIsMissing() {
        contextRunner.withPropertyValues("inventory.jwt.secret=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("jwt.secret"));
    }

    @Test
    void failsToStartWhenSecretIsShorterThan32Bytes() {
        contextRunner.withPropertyValues("inventory.jwt.secret=" + SECRET_OF_32_BYTES.substring(1))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("at least 32 bytes"));
    }
}
