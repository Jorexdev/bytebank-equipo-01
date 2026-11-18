package dev.bytebank.fraud.rules;

import dev.bytebank.fraud.FraudProperties;
import dev.bytebank.fraud.check.Verdict;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewAmountRuleTest {

    private final ReviewAmountRule rule = new ReviewAmountRule(new FraudProperties(
            new BigDecimal("20000"),
            new BigDecimal("1000"),
            new BigDecimal("5000"),
            5,
            Duration.ofMinutes(10)
    ));

    @Test
    void sendsAmountsAboveThresholdToReview() {
        var hit = rule.evaluate(context("5000.01"));

        assertThat(hit).isPresent();
        assertThat(hit.get().verdict()).isEqualTo(Verdict.REVISAR);
    }

    @Test
    void letsThresholdAmountThrough() {
        assertThat(rule.evaluate(context("5000.00"))).isEmpty();
    }

    private static FraudRule.Context context(String amount) {
        return new FraudRule.Context(
                "ES7639990001000000000001",
                "ES7639990001000000000002",
                2L,
                new BigDecimal(amount),
                Instant.now()
        );
    }
}
