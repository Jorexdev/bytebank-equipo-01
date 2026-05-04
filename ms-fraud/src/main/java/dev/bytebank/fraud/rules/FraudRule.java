package dev.bytebank.fraud.rules;

import dev.bytebank.fraud.check.Verdict;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** Regla antifraude. Para añadir una nueva basta con crear otro @Component que implemente esta interfaz. */
public interface FraudRule {

    Optional<Hit> evaluate(Context context);

    record Context(String originIban, String destinationIban, Long userId, BigDecimal amount, Instant now) {
    }

    record Hit(String code, Verdict verdict) {
    }
}
