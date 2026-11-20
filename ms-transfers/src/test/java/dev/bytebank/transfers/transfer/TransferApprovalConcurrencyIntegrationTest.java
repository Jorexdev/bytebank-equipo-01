package dev.bytebank.transfers.transfer;

import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import dev.bytebank.transfers.IntegrationTest;
import dev.bytebank.transfers.client.AccountsClient;
import dev.bytebank.transfers.client.FraudClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Contra Postgres real: dos gestores aprobando a la vez la misma transferencia pendiente.
 * El bloqueo pesimista de {@code findByIdForUpdate} debe hacer que solo uno mueva el dinero;
 * el otro tiene que recibir 409 TRANSFERENCIA_YA_RESUELTA.
 */
class TransferApprovalConcurrencyIntegrationTest extends IntegrationTest {

    static final String ORIGIN = Iban.generate(1);
    static final String DESTINATION = Iban.generate(2);
    static final AuthUser ANA = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);
    static final AuthUser GESTOR_A = new AuthUser(9L, "gestor.a@bytebank.test", Role.GESTOR);
    static final AuthUser GESTOR_B = new AuthUser(10L, "gestor.b@bytebank.test", Role.GESTOR);

    @Autowired
    TransferService service;
    @Autowired
    TransferRepository transfers;
    @MockBean
    AccountsClient accounts;
    @MockBean
    FraudClient fraud;

    @BeforeEach
    void setUp() {
        transfers.deleteAll();
    }

    @Test
    void twoGestoresApprovingTheSameTransferMoveTheMoneyOnlyOnce() throws Exception {
        Transfer pending = new Transfer(ORIGIN, DESTINATION, new BigDecimal("6000.00"), "Coche", ANA.id());
        pending.markPendingReview();
        Long id = transfers.save(pending).getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        int completed = 0;
        int rejectedAsAlreadyResolved = 0;
        try {
            List<Future<Transfer>> approvals = List.of(
                    pool.submit(approveWhenBothAreReady(id, GESTOR_A, ready, start)),
                    pool.submit(approveWhenBothAreReady(id, GESTOR_B, ready, start)));

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<Transfer> approval : approvals) {
                try {
                    assertThat(approval.get(20, TimeUnit.SECONDS).getStatus()).isEqualTo(TransferStatus.COMPLETADA);
                    completed++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOfSatisfying(ApiException.class, conflict -> {
                        assertThat(conflict.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(conflict.getCode()).isEqualTo("TRANSFERENCIA_YA_RESUELTA");
                    });
                    rejectedAsAlreadyResolved++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(completed).isEqualTo(1);
        assertThat(rejectedAsAlreadyResolved).isEqualTo(1);
        // Un solo movimiento en el ledger: el perdedor no llegó a ejecutar la transferencia.
        verify(accounts, times(1)).debit(eq("TRF-" + id), eq(ORIGIN), any(), any());
        verify(accounts, times(1)).credit(eq("TRF-" + id), eq(DESTINATION), any(), any());
        assertThat(transfers.findById(id).orElseThrow().getStatus()).isEqualTo(TransferStatus.COMPLETADA);
    }

    private Callable<Transfer> approveWhenBothAreReady(Long id, AuthUser gestor, CountDownLatch ready,
                                                       CountDownLatch start) {
        return () -> {
            ready.countDown();
            start.await();
            return service.approve(id, gestor);
        };
    }
}
