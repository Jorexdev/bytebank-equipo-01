package dev.bytebank.cards.card;

import dev.bytebank.cards.client.AccountsClient;
import dev.bytebank.cards.payment.CardPayment;
import dev.bytebank.cards.payment.CardPaymentRepository;
import dev.bytebank.cards.payment.PaymentStatus;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.http.RemoteUnavailableException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;

@Service
public class CardService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CardRepository cards;
    private final CardPaymentRepository payments;
    private final AccountsClient accountsClient;
    private final PaymentReserver reserver;
    private final BigDecimal defaultDailyLimit;

    public CardService(CardRepository cards, CardPaymentRepository payments, AccountsClient accountsClient, PaymentReserver reserver,
                       @Value("${bytebank.cards.default-daily-limit}") BigDecimal defaultDailyLimit) {
        this.cards = cards;
        this.payments = payments;
        this.accountsClient = accountsClient;
        this.reserver = reserver;
        this.defaultDailyLimit = defaultDailyLimit;
    }

    /** Emite una tarjeta de débito para una cuenta del usuario. */
    public Card issue(String iban, AuthUser user) {
        var account = accountsClient.getAccount(Iban.normalize(iban));
        if (!account.isHolder(user.id())) {
            throw ApiException.notFound("CUENTA_NO_ENCONTRADA", "No existe la cuenta " + iban);
        }
        if (!account.isActive()) {
            throw ApiException.conflict("CUENTA_NO_OPERATIVA", "La cuenta no está operativa");
        }
        var last4 = String.format("%04d", RANDOM.nextInt(10_000));
        return cards.save(new Card(account.iban(), user.id(), last4, defaultDailyLimit));
    }

    public List<Card> listOwn(AuthUser user) {
        return cards.findByUserIdOrderByCreatedAt(user.id());
    }

    public Card get(Long id, AuthUser user) {
        var card = cards.findById(id).orElseThrow(() -> notFound(id));
        if (!card.getUserId().equals(user.id())) {
            throw notFound(id);
        }
        return card;
    }

    public Card changeStatus(Long id, CardStatus status, AuthUser user) {
        var card = get(id, user);
        card.changeStatus(status);
        return cards.save(card);
    }

    /** Cobra un pago con la tarjeta. El cargo en ms-accounts se hace fuera de la transacción del bloqueo. */
    public CardPayment pay(Long id, BigDecimal amount, String merchant, AuthUser user) {
        var card = get(id, user);
        var payment = reserver.reserve(id, amount, merchant, user);
        if (payment.getStatus() == PaymentStatus.RECHAZADO) {
            return payment;
        }
        try {
            accountsClient.debit(payment.ledgerReference(), card.getAccountIban(), amount, merchant);
        } catch (ApiException e) {
            payment.reject(e.getCode());
            payments.save(payment);
            // Si ms-accounts no responde no sabemos si cobró: se avisa con 503 en lugar de un rechazo.
            if (e instanceof RemoteUnavailableException) {
                throw e;
            }
        }
        return payment;
    }

    public List<CardPayment> payments(Long id, AuthUser user) {
        var card = get(id, user);
        return payments.findByCardIdOrderByCreatedAtDescIdDesc(card.getId());
    }

    private static ApiException notFound(Long id) {
        return ApiException.notFound("TARJETA_NO_ENCONTRADA", "No existe la tarjeta " + id);
    }
}
