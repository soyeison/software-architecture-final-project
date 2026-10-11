package com.retail.inventory.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reloj del servicio en UTC. Avanza en milisegundos, de modo que todo instante queda truncado a
 * milisegundos (spec §4). Se inyecta por constructor para que las pruebas puedan fijar la hora.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofMillis(1));
    }
}
