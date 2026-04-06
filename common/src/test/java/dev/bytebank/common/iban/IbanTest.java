package dev.bytebank.common.iban;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IbanTest {

    @Test
    void generatedIbansAreValidAndBelongToByteBank() {
        for (long n = 1; n <= 100; n++) {
            String iban = Iban.generate(n);
            assertThat(Iban.isValid(iban)).as(iban).isTrue();
            assertThat(Iban.isOwnBank(iban)).as(iban).isTrue();
        }
    }

    @Test
    void acceptsIbanFromAnotherBank() {
        assertThat(Iban.isValid("ES91 2100 0418 4502 0005 1332")).isTrue();
        assertThat(Iban.isOwnBank("ES9121000418450200051332")).isFalse();
    }

    @Test
    void rejectsForeignIban() {
        assertThat(Iban.isValid("FR7630006000011234567890189")).isFalse();
    }
}
