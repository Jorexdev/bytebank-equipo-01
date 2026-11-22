package dev.bytebank.cards.card;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytebank.cards.client.AccountsClient;
import dev.bytebank.cards.client.AccountsClient.AccountInfo;
import dev.bytebank.cards.payment.CardPayment;
import dev.bytebank.cards.payment.CardPaymentRepository;
import dev.bytebank.cards.payment.PaymentStatus;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.http.RemoteUnavailableException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CardServiceTest {

    static final String IBAN = Iban.generate(1);
    static final AuthUser ANA = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);
    // 00:30 del 13 de junio en Madrid: el "hoy" de Madrid empieza el 12 a las 22:00 UTC.
    static final Instant NOW = Instant.parse("2026-06-12T22:30:00Z");
    static final Instant MADRID_MIDNIGHT = Instant.parse("2026-06-12T22:00:00Z");

    CardRepository cards = mock(CardRepository.class);
    AccountsClient accounts = mock(AccountsClient.class);
    CardPaymentRepository payments = mock(CardPaymentRepository.class);
    CardService service = new CardService(cards, payments, accounts,
            new PaymentReserver(cards, payments, Clock.fixed(NOW, ZoneOffset.UTC), "Europe/Madrid"),
            new BigDecimal("600.00"));

    @BeforeEach
    void setUp() {
        when(cards.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(payments.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(payments.sumAmountSince(any(), any())).thenReturn(BigDecimal.ZERO);
    }

    // ---- emitir ----

    @Test
    void issuesActiveCardWithDefaultLimit() {
        account("ACTIVA", 2L);

        Card card = service.issue(IBAN, ANA);

        assertThat(card.getStatus()).isEqualTo(CardStatus.ACTIVA);
        assertThat(card.getDailyLimit()).isEqualByComparingTo("600");
        assertThat(card.getAccountIban()).isEqualTo(IBAN);
        assertThat(card.getUserId()).isEqualTo(2L);
        assertThat(card.getLast4()).matches("\\d{4}");
    }

    /** El nuevo titular puede pedir tarjeta usando el JSON real de GET /internal/accounts/{iban}. */
    @Test
    void issueSucceedsForHolderFromInternalResponse() throws Exception {
        String json = """
                {"iban":"%s","status":"ACTIVA","balance":10.00,"ownerId":99,"holderIds":[99,2]}
                """.formatted(IBAN);
        AccountInfo info = new ObjectMapper().readValue(json, AccountInfo.class);
        when(accounts.getAccount(IBAN)).thenReturn(info);

        Card card = service.issue(IBAN, ANA);

        assertThat(info.holderIds()).containsExactly(99L, 2L);
        assertThat(card.getUserId()).isEqualTo(2L);
        assertThat(card.getAccountIban()).isEqualTo(IBAN);
        verify(cards).save(any());
    }

    @Test
    void issueNormalizesIban() {
        account("ACTIVA", 2L);

        assertThat(service.issue(" " + IBAN.toLowerCase() + " ", ANA).getAccountIban()).isEqualTo(IBAN);
    }

    @Test
    void issueFailsForForeignAccount() {
        account("ACTIVA", 99L);

        assertCode("CUENTA_NO_ENCONTRADA", () -> service.issue(IBAN, ANA));
        verify(cards, never()).save(any());
    }

    @Test
    void issueFailsForMissingAccount() {
        when(accounts.getAccount(IBAN)).thenThrow(ApiException.notFound("CUENTA_NO_ENCONTRADA", "no existe"));

        assertCode("CUENTA_NO_ENCONTRADA", () -> service.issue(IBAN, ANA));
    }

    @Test
    void issueFailsForInactiveAccount() {
        account("BLOQUEADA", 2L);

        assertCode("CUENTA_NO_OPERATIVA", () -> service.issue(IBAN, ANA));
        verify(cards, never()).save(any());
    }

    // ---- pagar: casos buenos ----

    @Test
    void approvedPaymentDebitsAccount() {
        ownCard();

        CardPayment p = pay("42.50");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.APROBADO);
        assertThat(p.getRejectionReason()).isNull();
        verify(accounts).debit(anyString(), eq(IBAN), eq(new BigDecimal("42.50")), eq("Tienda"));
    }

    @Test
    void paymentUpToTheLimitIsApproved() {
        ownCard();
        spentToday("590.00");

        assertThat(pay("10.00").getStatus()).isEqualTo(PaymentStatus.APROBADO);
    }

    @Test
    void smallestAmountIsApproved() {
        ownCard();

        assertThat(pay("0.01").getStatus()).isEqualTo(PaymentStatus.APROBADO);
    }

    // ---- pagar: rechazos (se guardan y no cargan) ----

    @Test
    void blockedCardIsRejected() {
        ownCard().changeStatus(CardStatus.BLOQUEADA);

        assertRejected("TARJETA_BLOQUEADA", pay("10"));
    }

    @Test
    void blockedWinsOverDailyLimit() {
        ownCard().changeStatus(CardStatus.BLOQUEADA);
        spentToday("600.00");

        assertRejected("TARJETA_BLOQUEADA", pay("10"));
    }

    @Test
    void paymentOverTheLimitByOneCentIsRejected() {
        ownCard();
        spentToday("590.00");

        assertRejected("LIMITE_DIARIO_SUPERADO", pay("10.01"));
    }

    @Test
    void singlePaymentOverTheLimitIsRejected() {
        ownCard();

        assertRejected("LIMITE_DIARIO_SUPERADO", pay("600.01"));
    }

    @Test
    void dailyLimitIsCountedFromMadridMidnight() {
        ownCard();
        // Solo devuelve gasto si se consulta desde las 00:00 de Madrid, no desde las 00:00 UTC.
        when(payments.sumAmountSince(any(), eq(MADRID_MIDNIGHT))).thenReturn(new BigDecimal("600.00"));

        assertRejected("LIMITE_DIARIO_SUPERADO", pay("0.01"));
    }

    // ---- pagar: errores de ms-accounts ----

    @Test
    void accountErrorRejectsPaymentWithItsCode() {
        ownCard();
        debitFails(ApiException.conflict("FONDOS_INSUFICIENTES", "Saldo insuficiente"));

        CardPayment p = pay("10");

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.RECHAZADO);
        assertThat(p.getRejectionReason()).isEqualTo("FONDOS_INSUFICIENTES");
    }

    @Test
    void accountsDownSurfacesUnavailableAndKeepsRejection() {
        ownCard();
        debitFails(new RemoteUnavailableException("caído"));

        assertCode("SERVICIO_NO_DISPONIBLE", () -> pay("10"));
        verify(payments, times(2)).save(any()); // reserva + rechazo guardado
    }

    // ---- pagar: tarjeta que no es del usuario ----

    @Test
    void foreignCardIsNotFound() {
        ownCard(99L);

        assertCode("TARJETA_NO_ENCONTRADA", () -> pay("10"));
        verify(payments, never()).save(any());
    }

    @Test
    void missingCardIsNotFound() {
        assertCode("TARJETA_NO_ENCONTRADA", () -> pay("10"));
    }

    // ---- ayudas ----

    private void account(String status, Long ownerId) {
        when(accounts.getAccount(IBAN)).thenReturn(new AccountInfo(IBAN, status, BigDecimal.TEN, ownerId));
    }

    private Card ownCard() {
        return ownCard(2L);
    }

    private Card ownCard(Long ownerId) {
        Card card = new Card(IBAN, ownerId, "4821", new BigDecimal("600.00"));
        when(cards.findById(1L)).thenReturn(Optional.of(card));
        when(cards.findWithLockById(1L)).thenReturn(Optional.of(card));
        return card;
    }

    private void spentToday(String amount) {
        when(payments.sumAmountSince(any(), eq(MADRID_MIDNIGHT))).thenReturn(new BigDecimal(amount));
    }

    private void debitFails(ApiException e) {
        doThrow(e).when(accounts).debit(anyString(), anyString(), any(), anyString());
    }

    private CardPayment pay(String amount) {
        return service.pay(1L, new BigDecimal(amount), "Tienda", ANA);
    }

    /** Rechazado con ese motivo, guardado para el historial y sin tocar la cuenta. */
    private void assertRejected(String reason, CardPayment p) {
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.RECHAZADO);
        assertThat(p.getRejectionReason()).isEqualTo(reason);
        verify(payments).save(p);
        verify(accounts, never()).debit(anyString(), anyString(), any(), anyString());
    }

    private static void assertCode(String code, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }
}
