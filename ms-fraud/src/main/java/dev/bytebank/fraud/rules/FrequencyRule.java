package dev.bytebank.fraud.rules;

import dev.bytebank.fraud.check.FraudCheckRepository;
import dev.bytebank.fraud.check.Verdict;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** Más de 5 transferencias desde la misma cuenta en 10 minutos: se bloquea la sexta. */
@Component
public class FrequencyRule implements FraudRule {

    public static final String CODE = "FRECUENCIA_ALTA";
    static final int MAX_TRANSFERS = 5;
    static final Duration WINDOW = Duration.ofMinutes(10);

    private final FraudCheckRepository checks;

    public FrequencyRule(FraudCheckRepository checks) {
        this.checks = checks;
    }

    @Override
    public Optional<Hit> evaluate(Context context) {
        long previous = checks.countSince(context.originIban(), Verdict.APROBADA, context.now().minus(WINDOW));
        if (previous >= MAX_TRANSFERS) {
            return Optional.of(new Hit(CODE, Verdict.BLOQUEADA));
        }
        return Optional.empty();
    }
}