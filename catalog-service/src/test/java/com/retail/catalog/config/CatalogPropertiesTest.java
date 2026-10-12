package com.retail.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class CatalogPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(CatalogProperties.class)
    static class PropertiesConfiguration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    private static final String[] VALID = { "catalog.consumer.queue=catalog.inventory-stock-changed",
            "catalog.consumer.initial-backoff=1s", "catalog.consumer.maximum-backoff=30s" };

    @Test
    void bindsTheConsumerProperties() {
        runner.withPropertyValues(VALID).run(context -> {
            CatalogProperties.Consumer consumer = context.getBean(CatalogProperties.class).consumer();
            assertThat(consumer.queue()).isEqualTo("catalog.inventory-stock-changed");
            assertThat(consumer.initialBackoff()).isEqualTo(Duration.ofSeconds(1));
            assertThat(consumer.maximumBackoff()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void failsToStartWithoutTheConsumerSection() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsToStartWithABlankQueue() {
        runner.withPropertyValues(VALID).withPropertyValues("catalog.consumer.queue= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsToStartWithANonPositiveInitialBackoff() {
        runner.withPropertyValues(VALID).withPropertyValues("catalog.consumer.initial-backoff=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsToStartWhenTheMaximumIsShorterThanTheInitialBackoff() {
        runner.withPropertyValues(VALID).withPropertyValues("catalog.consumer.maximum-backoff=500ms")
                .run(context -> assertThat(context).hasFailed());
    }
}
