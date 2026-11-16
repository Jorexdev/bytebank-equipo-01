package dev.bytebank.cards.card;

import dev.bytebank.cards.IntegrationTest;
import dev.bytebank.cards.client.AccountsClient;
import dev.bytebank.cards.payment.CardPayment;
import dev.bytebank.cards.payment.CardPaymentRepository;
import dev.bytebank.cards.payment.PaymentStatus;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.http.RemoteUnavailableException;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/** Contra Postgres real: la consulta del límite diario, el bloqueo de la tarjeta y lo que se guarda de verdad. */
class CardPaymentIntegrationTest extends IntegrationTest {

    static final AuthUser ANA = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);

    @Autowired
    CardService service;
    @Autowired
    CardRepository cards;
    @Autowired
    CardPaymentRepository payments;
    @MockBean
    AccountsClient accounts;

    Card card;

    @BeforeEach
    void setUp() {
        payments.deleteAll();
        cards.deleteAll();
        card = cards.save(new Card("ES7639990001000000000001", 2L, "4821", new BigDecimal("600.00")));
    }

    @Test
    void rejectedPaymentsDoNotCountTowardsTheLimit() {
        service.pay(card.getId(), new BigDecimal("500"), "Tienda", ANA);                // aprobado
        assertThat(service.pay(card.getId(), new BigDecimal("200"), "Tienda", ANA).getStatus())
                .isEqualTo(PaymentStatus.RECHAZADO);                                    // supera 600
        assertThat(service.pay(card.getId(), new BigDecimal("100"), "Tienda", ANA).getStatus())
                .isEqualTo(PaymentStatus.APROBADO);                                     // 500 + 100: el rechazo no suma

        assertThat(payments.sumAmountSince(card.getId(), Instant.EPOCH)).isEqualByComparingTo("600");
        assertThat(service.payments(card.getId(), ANA)).hasSize(3);
    }

    @Test
    void sumIsZeroWithoutPaymentsAndIgnoresOtherCards() {
        Card other = cards.save(new Card("ES7639990001000000000002", 2L, "1111", BigDecimal.TEN));
        service.pay(other.getId(), new BigDecimal("5"), "Tienda", ANA);

        assertThat(payments.sumAmountSince(card.getId(), Instant.EPOCH)).isEqualByComparingTo("0");
    }

    @Test
    void sumOnlyCountsPaymentsSinceTheGivenInstant() {
        service.pay(card.getId(), new BigDecimal("600"), "Tienda", ANA);

        assertThat(payments.sumAmountSince(card.getId(), Instant.now().plusSeconds(3600))).isEqualByComparingTo("0");
    }

    @Test
    void accountsDownLeavesPaymentRejectedInTheDatabase() {
        doThrow(new RemoteUnavailableException("caído")).when(accounts).debit(anyString(), anyString(), any(), anyString());

        assertThatThrownBy(() -> service.pay(card.getId(), BigDecimal.TEN, "Tienda", ANA)).isInstanceOf(ApiException.class);

        CardPayment saved = payments.findAll().get(0);
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.RECHAZADO);
        assertThat(saved.getRejectionReason()).isEqualTo("SERVICIO_NO_DISPONIBLE");
    }

    @Test
    void simultaneousPaymentsCannotExceedTheDailyLimit() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        List<Callable<CardPayment>> tasks = java.util.Collections.nCopies(8,
                () -> service.pay(card.getId(), new BigDecimal("200"), "Tienda", ANA));

        long approved = pool.invokeAll(tasks).stream().map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).filter(p -> p.getStatus() == PaymentStatus.APROBADO).count();
        pool.shutdown();

        assertThat(approved).isEqualTo(3); // 600 / 200
        assertThat(payments.sumAmountSince(card.getId(), Instant.EPOCH)).isEqualByComparingTo("600");
    }
}
