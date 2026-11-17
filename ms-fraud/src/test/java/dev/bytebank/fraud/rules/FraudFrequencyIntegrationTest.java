package dev.bytebank.fraud.rules;

import dev.bytebank.fraud.check.FraudDtos;
import dev.bytebank.fraud.check.FraudService;
import dev.bytebank.fraud.check.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

class FraudFrequencyIntegrationTest extends IntegrationTest {

    @Autowired
    FraudService fraud;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from fraud_checks");
    }

    @Test
    void concurrentChecksBlockFromTheSixthOne() throws Exception {
        int threads = 10;
        String origin = "ES7639990001000000000001";
        String destination = "ES7639990001000000000002";
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Verdict>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return fraud.check(new FraudDtos.CheckRequest(origin, destination, 2L, new BigDecimal("10"))).verdict();
                }));
            }
            ready.await();
            start.countDown();

            int approved = 0;
            int blocked = 0;
            for (Future<Verdict> f : futures) {
                Verdict v = f.get(15, TimeUnit.SECONDS);
                if (v == Verdict.APROBADA) approved++;
                if (v == Verdict.BLOQUEADA) blocked++;
            }

            assertThat(approved).isEqualTo(5);
            assertThat(blocked).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }
}
