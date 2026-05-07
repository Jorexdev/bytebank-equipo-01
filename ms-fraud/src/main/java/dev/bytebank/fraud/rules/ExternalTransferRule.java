package dev.bytebank.fraud.rules;

import dev.bytebank.common.iban.Iban;
import dev.bytebank.fraud.FraudProperties;
import dev.bytebank.fraud.check.Verdict;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Las transferencias a otras entidades a partir de cierto importe las revisa un gestor. */
@Component
public class ExternalTransferRule implements FraudRule {

    public static final String CODE = "EXTERNA_IMPORTE_ALTO";

    private final FraudProperties properties;

    public ExternalTransferRule(FraudProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<Hit> evaluate(Context context) {
        boolean external = !Iban.isOwnBank(context.destinationIban());
        if (external && context.amount().compareTo(properties.externalReviewThreshold()) >= 0) {
            return Optional.of(new Hit(CODE, Verdict.REVISAR));
        }
        return Optional.empty();
    }
}
