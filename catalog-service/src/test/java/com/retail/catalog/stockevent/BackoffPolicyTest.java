package com.retail.catalog.stockevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30));

    @ParameterizedTest
    @CsvSource({"1,1", "2,2", "3,4", "4,8", "5,16", "6,30", "7,30", "50,30"})
    void doublesTheDelayUpToTheMaximum(int attempt, long expectedSeconds) {
        assertThat(policy.delayAfter(attempt)).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    /** No hay límite de intentos: un número enorme de intentos sigue dando el tope, sin desbordar. */
    @Test
    void neverGivesUpNorOverflows() {
        assertThat(policy.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aMaximumEqualToTheInitialDelayKeepsItConstant() {
        BackoffPolicy constant = new BackoffPolicy(Duration.ofMillis(50), Duration.ofMillis(50));

        assertThat(constant.delayAfter(1)).isEqualTo(Duration.ofMillis(50));
        assertThat(constant.delayAfter(9)).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void attemptsStartAtOne() {
        assertThatThrownBy(() -> policy.delayAfter(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIncoherentConfiguration() {
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
