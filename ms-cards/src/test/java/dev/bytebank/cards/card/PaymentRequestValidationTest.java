package dev.bytebank.cards.card;

import dev.bytebank.cards.card.CardDtos.PaymentRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentRequestValidationTest {

    static final jakarta.validation.Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @CsvSource({"0.01,true", "42.50,true", "42,true", "0,false", "-5,false", "42.505,false"})
    void amountMustBePositiveWithMaxTwoDecimals(String amount, boolean valid) {
        assertThat(VALIDATOR.validate(new PaymentRequest(new BigDecimal(amount), "Tienda")).isEmpty()).isEqualTo(valid);
    }

    @Test
    void amountAndMerchantAreRequired() {
        assertThat(VALIDATOR.validate(new PaymentRequest(null, "Tienda"))).hasSize(1);
        assertThat(VALIDATOR.validate(new PaymentRequest(BigDecimal.TEN, " "))).hasSize(1);
        assertThat(VALIDATOR.validate(new PaymentRequest(BigDecimal.TEN, "x".repeat(101)))).hasSize(1);
    }
}
