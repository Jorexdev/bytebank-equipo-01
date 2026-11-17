package dev.bytebank.cards.card;

import dev.bytebank.cards.payment.CardPayment;
import dev.bytebank.cards.payment.CardPaymentRepository;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;

/**
 * Valida y guarda el intento de pago bajo bloqueo de la tarjeta, en una transacción corta: dos pagos simultáneos no
 * pueden superar el límite porque el segundo ve el primero ya guardado. Va aparte de {@link CardService} para que el
 * cargo HTTP se haga con la transacción ya cerrada.
 */
@Component
class PaymentReserver {

    private final CardRepository cards;
    private final CardPaymentRepository payments;
    private final Clock clock;
    private final ZoneId zone;

    PaymentReserver(CardRepository cards, CardPaymentRepository payments, Clock clock,
                    @Value("${bytebank.zone:Europe/Madrid}") String zone) {
        this.cards = cards;
        this.payments = payments;
        this.clock = clock;
        this.zone = ZoneId.of(zone);
    }

    @Transactional
    CardPayment reserve(Long id, BigDecimal amount, String merchant, AuthUser user) {
        var card = cards.findWithLockById(id).filter(c -> c.getUserId().equals(user.id()))
                .orElseThrow(() -> ApiException.notFound("TARJETA_NO_ENCONTRADA", "No existe la tarjeta " + id));
        var now = clock.instant();
        var payment = new CardPayment(card.getId(), amount, merchant, now);
        // Pagos aprobados desde las 00:00 de hoy, hora de Madrid.
        var since = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();

        if (!card.isActive()) {
            payment.reject("TARJETA_BLOQUEADA");
        } else if (payments.sumAmountSince(card.getId(), since).add(amount).compareTo(card.getDailyLimit()) > 0) {
            payment.reject("LIMITE_DIARIO_SUPERADO");
        } else {
            payment.approve();
        }
        return payments.save(payment); // el id se necesita para la referencia del ledger (CARD-<id>)
    }
}
