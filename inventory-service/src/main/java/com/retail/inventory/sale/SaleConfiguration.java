package com.retail.inventory.sale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.retail.inventory.config.InventoryProperties;

@Configuration
public class SaleConfiguration {

    @Bean
    OptimisticRetry optimisticRetry(InventoryProperties properties) {
        return new OptimisticRetry(properties.sale().maxAttempts());
    }
}
