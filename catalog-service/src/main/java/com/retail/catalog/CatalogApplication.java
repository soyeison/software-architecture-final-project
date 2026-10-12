package com.retail.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Servicio de Catálogo: datos de producto y vista de disponibilidad alimentada por eventos.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CatalogApplication {

    public static void main(String[] arguments) {
        SpringApplication.run(CatalogApplication.class, arguments);
    }
}
