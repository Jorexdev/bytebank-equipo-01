package dev.bytebank.cards.card;

import dev.bytebank.cards.payment.CardPayment;
import dev.bytebank.cards.payment.PaymentStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class CardDtos {

    private CardDtos() {
    }

    public record IssueCardRequest(@NotBlank String iban) {
    }

    public record ChangeCardStatusRequest(@NotNull CardStatus status) {
    }

    public record PaymentRequest(
            @NotNull @DecimalMin(value = "0.01", message = "el importe debe ser positivo")
            @Digits(integer = 15, fraction = 2, message = "como mucho 2 decimales") BigDecimal amount,
            @NotBlank @Size(max = 100) String merchant) {
    }

    public record CardResponse(Long id, String accountIban, String maskedNumber, CardStatus status,
                               BigDecimal dailyLimit, Instant createdAt) {

        public static CardResponse from(Card card) {
            return new CardResponse(card.getId(), card.getAccountIban(), "**** **** **** " + card.getLast4(),
                    card.getStatus(), card.getDailyLimit(), card.getCreatedAt());
        }
    }

    public record PaymentResponse(Long id, Long cardId, BigDecimal amount, String merchant, PaymentStatus status,
                                  String rejectionReason, Instant createdAt) {

        public static PaymentResponse from(CardPayment payment) {
            return new PaymentResponse(payment.getId(), payment.getCardId(), payment.getAmount(),
                    payment.getMerchant(), payment.getStatus(), payment.getRejectionReason(), payment.getCreatedAt());
        }
    }
}
