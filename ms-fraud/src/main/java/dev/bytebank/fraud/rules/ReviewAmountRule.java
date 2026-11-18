package dev.bytebank.fraud.rules;

import dev.bytebank.fraud.FraudProperties;
import dev.bytebank.fraud.check.Verdict;
import org.springframework.stereotype.Component;
import java.util.Optional;

/** Los importes por encima del umbral, a cualquier destino, los revisa un gestor. */
@Component
public class ReviewAmountRule implements FraudRule {

    public static final String CODE = "IMPORTE_REVISION";

    private final FraudProperties properties;

    public ReviewAmountRule(FraudProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<Hit> evaluate(Context context) {
        if (context.amount().compareTo(properties.reviewThreshold()) > 0) {
            return Optional.of(new Hit(CODE, Verdict.REVISAR));
        }
        return Optional.empty();
    }
}
