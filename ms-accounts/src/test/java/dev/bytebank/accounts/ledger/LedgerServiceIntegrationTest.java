package dev.bytebank.accounts.ledger;

import dev.bytebank.accounts.IntegrationTest;
import dev.bytebank.accounts.account.Account;
import dev.bytebank.accounts.account.AccountRepository;
import dev.bytebank.accounts.account.AccountService;
import dev.bytebank.accounts.account.AccountStatus;
import dev.bytebank.accounts.ledger.LedgerDtos.MovementRequest;
import dev.bytebank.accounts.movement.MovementType;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.accounts.event.MovementEvent;
import dev.bytebank.accounts.event.OutboxRelay;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

class LedgerServiceIntegrationTest extends IntegrationTest {

    @Autowired
    LedgerService ledger;
    @Autowired
    AccountRepository accounts;
    @Autowired
    AccountService accountService;
    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;
    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    String iban;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbox");
        jdbc.update("delete from movements");
        jdbc.update("delete from accounts");
        Account account = new Account(Iban.generate(accounts.nextAccountNumber()), 1L);
        account.credit(new BigDecimal("100.00"));
        iban = accounts.save(account).getIban();
    }

    @Test
    void debitReducesBalance() {
        ledger.debit(new MovementRequest("TRF-1", iban, new BigDecimal("30.0"), MovementType.TRANSFERENCIA_EMITIDA, null));

        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualByComparingTo("70");
    }

    @Test
    void creditIncreasesBalance() {
        ledger.credit(new MovementRequest("TRF-2", iban, new BigDecimal("50.0"), MovementType.TRANSFERENCIA_RECIBIDA, null));

        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualByComparingTo("150");
    }

    @Test
    void balanceIsExactWithFractionalCredits() {
        // Regresión del bug: 0,10 + 0,20 en coma flotante daba 0,30000000000000004
        ledger.credit(new MovementRequest("R-1", iban, new BigDecimal("0.10"), MovementType.INGRESO, null));
        ledger.credit(new MovementRequest("R-2", iban, new BigDecimal("0.20"), MovementType.INGRESO, null));

        // el setUp ya abona 100.00: 100.00 + 0.10 + 0.20
        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualByComparingTo("100.30");
    }

    @Test
    void cannotDebitMoreThanBalance() {
        assertThatThrownBy(() -> ledger.debit(new MovementRequest("TRF-3", iban, new BigDecimal("101.0"), MovementType.TRANSFERENCIA_EMITIDA, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void cannotCreditBlockedAccount() {
        Account account = accounts.findByIban(iban).orElseThrow();
        account.changeStatus(AccountStatus.BLOQUEADA);
        accounts.save(account);

        assertThatThrownBy(() -> ledger.credit(new MovementRequest("TRF-4", iban, new BigDecimal("10.0"), MovementType.TRANSFERENCIA_RECIBIDA, null)))
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
                    ledger.debit(new MovementRequest(ref, iban, new BigDecimal("30.0"), MovementType.TRANSFERENCIA_EMITIDA, null));
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
        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select count(*) from movements", Integer.class)).isEqualTo(3);
    }


    @Test
    void rolledBackMovementIsNeverPublished() {
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
            ledger.credit(new MovementRequest("TRF-RB", iban, new BigDecimal("50"), MovementType.TRANSFERENCIA_RECIBIDA, null));
            throw new IllegalStateException("fallo tras guardar el movimiento");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isZero();
        // Sin espera fija: un movimiento confirmado después llega seguro, y Kafka conserva el orden por IBAN,
        // así que si el revertido hubiera salido habría llegado antes y aparecería aquí.
        ledger.credit(new MovementRequest("TRF-AFTER-RB", iban, BigDecimal.ONE, MovementType.TRANSFERENCIA_RECIBIDA, null));
        assertThat(eventsFor(iban, Duration.ofSeconds(10))).singleElement()
                .satisfies(e -> assertThat(e.value()).contains("TRF-AFTER-RB"));
    }

    @Test
    void committedMovementIsPublishedAndOutboxIsDrained() {
        ledger.credit(new MovementRequest("TRF-OK", iban, new BigDecimal("50"), MovementType.TRANSFERENCIA_RECIBIDA, null));

        assertThat(eventsFor(iban, Duration.ofSeconds(10))).hasSize(1);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isZero());
    }

    @Test
    void committedDepositIsPublished() {
        accountService.deposit(iban, new BigDecimal("25"), "Ingreso de ventanilla");

        assertThat(eventsFor(iban, Duration.ofSeconds(10))).singleElement()
                .satisfies(e -> assertThat(e.value()).contains("INGRESO").contains("Ingreso de ventanilla"));
    }

    @Test
    void rolledBackDepositIsNeverPublished() {
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
            accountService.deposit(iban, new BigDecimal("25"), "Ingreso revertido");
            throw new IllegalStateException("fallo tras guardar el movimiento");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualByComparingTo("100");

        accountService.deposit(iban, BigDecimal.ONE, "Ingreso confirmado"); // mismo truco que en rolledBackMovementIsNeverPublished
        assertThat(eventsFor(iban, Duration.ofSeconds(10))).singleElement()
                .satisfies(e -> assertThat(e.value()).contains("Ingreso confirmado"));
    }

    @Test
    void concurrentRelaysKeepOrderPerIban() throws Exception {
        for (int i = 0; i < 150; i++) { // más de un lote, para que haya dos vaciados seguidos
            ledger.credit(new MovementRequest("TRF-O" + i, iban, BigDecimal.ONE, MovementType.TRANSFERENCIA_RECIBIDA, null));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> drains = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            drains.add(pool.submit(relay::drain));
        }
        for (Future<?> f : drains) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<String> references = eventsFor(iban, Duration.ofSeconds(15), 150).stream()
                .map(r -> r.value().replaceAll(".*\"reference\":\"(TRF-O\\d+)\".*", "$1")).distinct().toList();
        assertThat(references).hasSize(150)
                .isSortedAccordingTo(java.util.Comparator.comparingInt(r -> Integer.parseInt(r.substring(5))));
    }

    /** Eventos del topic para ese IBAN; espera hasta {@code wait} (o hasta ver {@code expected}). */
    private List<ConsumerRecord<String, String>> eventsFor(String key, Duration wait) {
        return eventsFor(key, wait, 1);
    }

    private List<ConsumerRecord<String, String>> eventsFor(String key, Duration wait, int expected) {
        var props = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + System.nanoTime(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of(MovementEvent.TOPIC));
            long end = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < end && found.size() < expected) {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (key.equals(r.key())) found.add(r);
                });
            }
        }
        return found;
    }

    @Test
    void debitFailsWithAccountBusyWhenRowIsLocked() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                accounts.findByIbanForUpdate(iban).orElseThrow();

                Future<?> attempt = pool.submit(() ->
                        ledger.debit(new MovementRequest("TRF-LOCK", iban, new BigDecimal("30.0"), MovementType.TRANSFERENCIA_EMITIDA, null)));

                assertThatThrownBy(() -> attempt.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(ApiException.class)
                        .satisfies(e -> assertThat(((ApiException) e.getCause()).getCode()).isEqualTo("CUENTA_OCUPADA"));
            });
        } finally {
            pool.shutdownNow();
        }
        
        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("select count(*) from movements", Integer.class)).isZero();
    }
}
