package com.retail.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Servicio de Inventario: única fuente de verdad del stock.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class InventoryApplication {

    public static void main(String[] arguments) {
        SpringApplication.run(InventoryApplication.class, arguments);
    }
}
