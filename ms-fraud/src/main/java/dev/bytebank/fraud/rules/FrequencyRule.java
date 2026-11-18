package dev.bytebank.fraud.rules;

import dev.bytebank.fraud.FraudProperties;
import dev.bytebank.fraud.check.FraudCheckRepository;
import dev.bytebank.fraud.check.Verdict;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** Más de 5 transferencias desde la misma cuenta en 10 minutos: se bloquea la sexta. */
@Component
public class FrequencyRule implements FraudRule {

    public static final String CODE = "FRECUENCIA_ALTA";

    private final FraudCheckRepository checks;
    private final FraudProperties properties;

    public FrequencyRule(FraudCheckRepository checks, FraudProperties properties) {
        this.checks = checks;
        this.properties = properties;
    }

    @Override
    public Optional<Hit> evaluate(Context context) {
        long previous = checks.countSince(context.originIban(), Verdict.APROBADA, context.now().minus(properties.frequencyWindow()));
        if (previous >= properties.maxTransfers()) {
            return Optional.of(new Hit(CODE, Verdict.BLOQUEADA));
        }
        return Optional.empty();
    }
}