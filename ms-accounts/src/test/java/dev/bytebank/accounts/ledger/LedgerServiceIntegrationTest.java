package dev.bytebank.accounts.ledger;

import dev.bytebank.accounts.IntegrationTest;
import dev.bytebank.accounts.account.Account;
import dev.bytebank.accounts.account.AccountRepository;
import dev.bytebank.accounts.account.AccountStatus;
import dev.bytebank.accounts.ledger.LedgerDtos.MovementRequest;
import dev.bytebank.accounts.movement.MovementType;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServiceIntegrationTest extends IntegrationTest {

    @Autowired
    LedgerService ledger;
    @Autowired
    AccountRepository accounts;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;

    String iban;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from movements");
        jdbc.update("delete from accounts");
        Account account = new Account(Iban.generate(accounts.nextAccountNumber()), 1L);
        account.credit(100);
        iban = accounts.save(account).getIban();
    }

    @Test
    void debitReducesBalance() {
        ledger.debit(new MovementRequest("TRF-1", iban, 30, MovementType.TRANSFERENCIA_EMITIDA, null));

        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualTo(70);
    }

    @Test
    void creditIncreasesBalance() {
        ledger.credit(new MovementRequest("TRF-2", iban, 50, MovementType.TRANSFERENCIA_RECIBIDA, null));

        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualTo(150);
    }

    @Test
    void cannotDebitMoreThanBalance() {
        assertThatThrownBy(() -> ledger.debit(new MovementRequest("TRF-3", iban, 101, MovementType.TRANSFERENCIA_EMITIDA, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void cannotCreditBlockedAccount() {
        Account account = accounts.findByIban(iban).orElseThrow();
        account.changeStatus(AccountStatus.BLOQUEADA);
        accounts.save(account);

        assertThatThrownBy(() -> ledger.credit(new MovementRequest("TRF-4", iban, 10, MovementType.TRANSFERENCIA_RECIBIDA, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void concurrentDebitsNeverLeaveNegativeBalance() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            String ref = "TRF-C" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    ledger.debit(new MovementRequest(ref, iban, 30, MovementType.TRANSFERENCIA_EMITIDA, null));
                    ok.incrementAndGet();
                } catch (ApiException e) {
                    if ("FONDOS_INSUFICIENTES".equals(e.getCode())) {
                        insufficient.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
                return null;
            }));
        }

        ready.await();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(3);
        assertThat(insufficient.get()).isEqualTo(1);
        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualTo(10);
        assertThat(jdbc.queryForObject("select count(*) from movements", Integer.class)).isEqualTo(3);
    }


    @Test
    void debitFailsWithAccountBusyWhenRowIsLocked() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                accounts.findByIbanForUpdate(iban).orElseThrow();

                Future<?> attempt = pool.submit(() ->
                        ledger.debit(new MovementRequest("TRF-LOCK", iban, 30, MovementType.TRANSFERENCIA_EMITIDA, null)));

                assertThatThrownBy(() -> attempt.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(ApiException.class)
                        .satisfies(e -> assertThat(((ApiException) e.getCause()).getCode()).isEqualTo("CUENTA_OCUPADA"));
            });
        } finally {
            pool.shutdownNow();
        }
        
        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualTo(100);
        assertThat(jdbc.queryForObject("select count(*) from movements", Integer.class)).isZero();
    }
}
