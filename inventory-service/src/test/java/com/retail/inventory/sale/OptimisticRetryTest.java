package com.retail.inventory.sale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class OptimisticRetryTest {

    private final OptimisticRetry retry = new OptimisticRetry(3);

    @Test
    void returnsTheResultOnTheFirstAttempt() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.run(() -> {
            calls.incrementAndGet();
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(calls).hasValue(1);
    }

    @Test
    void succeedsAfterSomeConflicts() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.run(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new OptimisticLockingFailureException("conflict");
            }
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(calls).hasValue(3);
    }

    @Test
    void givesUpAfterTheMaximumAttemptsAndRethrowsTheConflict() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(() -> {
            calls.incrementAndGet();
            throw new OptimisticLockingFailureException("conflict");
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(calls).hasValue(3);
    }

    @Test
    void doesNotRetryOtherExceptions() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsLessThanOneAttempt() {
        assertThatThrownBy(() -> new OptimisticRetry(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
