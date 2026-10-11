package com.retail.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class ClockConfigurationTest {

    @Test
    void clockIsUtcAndTruncatesInstantsToMilliseconds() {
        Clock clock = new ClockConfiguration().clock();

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        for (int i = 0; i < 1000; i++) {
            assertThat(clock.instant().getNano() % 1_000_000).isZero();
        }
    }
}
