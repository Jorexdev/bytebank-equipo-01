package dev.bytebank.fraud.rules;

import dev.bytebank.fraud.check.FraudCheckRepository;
import dev.bytebank.fraud.check.Verdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FrequencyRuleTest {

    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

    @Test
    void blocksSixthTransferInWindow() {
        var hit = ruleWithPreviousTransfers(5).evaluate(context());

        assertThat(hit).isPresent();
        assertThat(hit.get().verdict()).isEqualTo(Verdict.BLOQUEADA);
        assertThat(hit.get().code()).isEqualTo("FRECUENCIA_ALTA");
    }

    @Test
    void letsFifthTransferThrough() {
        assertThat(ruleWithPreviousTransfers(4).evaluate(context())).isEmpty();
    }

    private static FrequencyRule ruleWithPreviousTransfers(long count) {
        FraudCheckRepository checks = mock(FraudCheckRepository.class);
        when(checks.countSince(any(), any(), any())).thenReturn(count);
        return new FrequencyRule(checks);
    }

    private static FraudRule.Context context() {
        return new FraudRule.Context("ES7639990001000000000001", "ES9121000418450200051332", 2L,
                new BigDecimal("150"), NOW);
    }
}
