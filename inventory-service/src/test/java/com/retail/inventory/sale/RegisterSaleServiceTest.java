package com.retail.inventory.sale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.retail.inventory.outbox.OutboxRecorder;
import com.retail.inventory.stock.Channel;
import com.retail.inventory.stock.Stock;
import com.retail.inventory.stock.StockChange;
import com.retail.inventory.stock.StockRepository;

class RegisterSaleServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private final StockRepository stockRepository = mock(StockRepository.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final OutboxRecorder outboxRecorder = mock(OutboxRecorder.class);
    private final RegisterSaleService service = new RegisterSaleService(stockRepository, transactionTemplate,
            new OptimisticRetry(3), outboxRecorder, Clock.fixed(NOW, ZoneOffset.UTC));

    @SuppressWarnings("unchecked")
    private void runCallbacksInline() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
    }

    @Test
    void persistentVersionConflictEndsInStockConflictAfterAllAttempts() {
        runCallbacksInline();
        Stock stock = new Stock("TENIS-RUNNER-42", 5, NOW);
        when(stockRepository.findById("TENIS-RUNNER-42")).thenReturn(Optional.of(stock));
        when(stockRepository.saveAndFlush(any())).thenThrow(new ObjectOptimisticLockingFailureException(Stock.class, "TENIS-RUNNER-42"));

        assertThatThrownBy(() -> service.register("TENIS-RUNNER-42", 1, Channel.POS))
                .isInstanceOf(StockConflictException.class);

        verify(stockRepository, times(3)).saveAndFlush(any());
    }

    @Test
    void conflictThatClearsOnRetrySucceeds() {
        runCallbacksInline();
        // Cada intento lee un registro nuevo, como la base real. Un Stock sin guardar no tiene versión
        // (la asigna JPA): se simula la posterior al guardado.
        when(stockRepository.findById("TENIS-RUNNER-42")).thenAnswer(invocation -> {
            Stock stock = spy(new Stock("TENIS-RUNNER-42", 5, NOW));
            doReturn(2L).when(stock).getVersion();
            return Optional.of(stock);
        });
        when(stockRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Stock.class, "TENIS-RUNNER-42"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StockChange change = service.register("TENIS-RUNNER-42", 2, Channel.ONLINE);

        assertThat(change.newStock()).isEqualTo(3);
        assertThat(change.stockVersion()).isEqualTo(2L);
        assertThat(change.channel()).isEqualTo(Channel.ONLINE);
        assertThat(change.occurredAt()).isEqualTo(NOW);
        assertThat(change.eventId()).isNotNull();
    }
}
