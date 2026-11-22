package dev.bytebank.transfers.transfer;

import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.http.RemoteUnavailableException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import dev.bytebank.common.web.PageResponse;
import dev.bytebank.transfers.client.AccountsClient;
import dev.bytebank.transfers.client.AccountsClient.AccountInfo;
import dev.bytebank.transfers.client.FraudClient;
import dev.bytebank.transfers.client.Verdict;
import dev.bytebank.transfers.transfer.TransferDtos.CreateTransferRequest;
import dev.bytebank.transfers.transfer.TransferDtos.TransferResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    static final String ORIGIN = Iban.generate(1);
    static final String DESTINATION = Iban.generate(2);
    static final AuthUser ANA = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);
    static final AuthUser GESTOR = new AuthUser(9L, "gestor@bytebank.test", Role.GESTOR);

    @Mock
    TransferRepository transfers;
    @Mock
    AccountsClient accounts;
    @Mock
    FraudClient fraud;

    TransferService service;

    @BeforeEach
    void setUp() {
        service = new TransferService(transfers, accounts, fraud);
        lenient().when(transfers.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accounts.getAccount(ORIGIN)).thenReturn(new AccountInfo(ORIGIN, "ACTIVA", new BigDecimal("1000"), 2L));
        lenient().when(accounts.getAccount(DESTINATION)).thenReturn(new AccountInfo(DESTINATION, "ACTIVA", new BigDecimal("100.00"), 7L));
        lenient().when(fraud.check(any(), any(), any(), any()))
                .thenReturn(new FraudClient.CheckResponse(1L, Verdict.APROBADA, List.of()));
    }

    @Test
    void completesTransferBetweenOwnAccounts() {
        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("100.00"), "Cena"), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETADA);
        verify(accounts, times(1)).debit(anyString(), eq(ORIGIN), any(), any());
    }

    @Test
    void completesTransferFromAccountWhereUserIsCoHolder() {
        when(accounts.getAccount(ORIGIN)).thenReturn(
                new AccountInfo(ORIGIN, "ACTIVA", new BigDecimal("1000"), 99L, List.of(99L, 2L)));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("100.00"), "Cena"), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETADA);
        verify(accounts, times(1)).debit(anyString(), eq(ORIGIN), any(), any());
    }

    @Test
    void rejectsWhenOriginHasNoFunds() {
        when(accounts.debit(anyString(), eq(ORIGIN), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "FONDOS_INSUFICIENTES", "Saldo insuficiente"));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("5000.00"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
    }

    @Test
    void rejectsWhenCreditFailsAfterDebit() {
        when(accounts.credit(anyString(), eq(DESTINATION), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "CUENTA_NO_OPERATIVA", "La cuenta no está activa"));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("50.00"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(t.getRejectionReason()).isEqualTo("CUENTA_NO_OPERATIVA");
    }

    @Test
    void blockedDestinationFailsBeforeDebit() {
        lenient().when(accounts.getAccount(DESTINATION))
                .thenReturn(new AccountInfo(DESTINATION, "BLOQUEADA", new BigDecimal("100.00"), 7L));

        assertThatThrownBy(() -> service.create(
                new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("50.00"), null), ANA))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "CUENTA_DESTINO_INACTIVA");

        verify(accounts, never()).debit(any(), any(), any(), any());
        verify(transfers, never()).save(any());
    }

    @Test
    void externalTransferDoesNotQueryDestinationAccount() {
        String external = "ES9121000418450200051332";

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, external, new BigDecimal("50.00"), "Alquiler"), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETADA);
        verify(accounts, never()).getAccount(external);
        verify(accounts, never()).credit(any(), any(), any(), any());
    }

    @Test
    void cannotTransferToSameAccount() {
        assertThatThrownBy(() -> service.create(new CreateTransferRequest(ORIGIN, ORIGIN, BigDecimal.TEN, null), ANA))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void blockedByFraudIsRejectedWithoutDebit() {
        when(fraud.check(any(), any(), any(), any())).thenReturn(new FraudClient.CheckResponse(2L, Verdict.BLOQUEADA, List.of("IMPORTE_MAXIMO")));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, BigDecimal.TEN, null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(t.getRejectionReason()).isEqualTo("FRAUDE");
        verify(accounts, never()).debit(any(), any(), any(), any());
        verify(accounts, never()).credit(any(), any(), any(), any());
        verify(transfers, times(1)).save(t);
    }

    @Test
    void reviewVerdictLeavesTransferPendingWithoutDebit() {
        when(fraud.check(any(), any(), any(), any())).thenReturn(new FraudClient.CheckResponse(3L, Verdict.REVISAR, List.of("IMPORTE_REVISION")));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, BigDecimal.TEN, null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.PENDIENTE_REVISION);
        verify(accounts, never()).debit(any(), any(), any(), any());
    }

    @Test
    void fraudServiceDownRejectsWithoutSavingNorDebiting() {
        when(fraud.check(any(), any(), any(), any())).thenThrow(new RemoteUnavailableException("caído"));

        assertThatThrownBy(() -> service.create(new CreateTransferRequest(ORIGIN, DESTINATION, BigDecimal.TEN, null), ANA)).isInstanceOf(RemoteUnavailableException.class);
        verify(transfers, never()).save(any());
        verify(accounts, never()).debit(any(), any(), any(), any());
    }

    @Test
    void cannotTransferFromAccountOfAnotherUser() {
        lenient().when(accounts.getAccount(ORIGIN))
                .thenReturn(new AccountInfo(ORIGIN, "ACTIVA", new BigDecimal("1000"), 99L));

        assertThatThrownBy(() -> service.create(
                new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("100.00"), null), ANA))
                .isInstanceOf(ApiException.class);
        verify(accounts, never()).debit(any(), any(), any(), any());
        verify(transfers, never()).save(any());
    }

    @Test
    void approvedVerdictExecutesEvenAboveTheAmountThatFraudNowReviews() {
        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("6000.00"), "Coche"), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETADA);
        verify(accounts, times(1)).debit(anyString(), eq(ORIGIN), eq(new BigDecimal("6000.00")), any());
    }

    @Test
    void reviewVerdictLeavesPendingWhateverTheAmount() {
        when(fraud.check(any(), any(), any(), any()))
                .thenReturn(new FraudClient.CheckResponse(3L, Verdict.REVISAR, List.of("EXTERNA_IMPORTE_ALTO")));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("1500.00"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.PENDIENTE_REVISION);
        verify(accounts, never()).debit(any(), any(), any(), any());
        verify(accounts, never()).credit(any(), any(), any(), any());
    }

    @Test
    void blockedFraudVerdictIsSavedAsRejectedInsteadOfFailing() {
        when(fraud.check(any(), any(), any(), any()))
                .thenReturn(new FraudClient.CheckResponse(4L, Verdict.BLOQUEADA, List.of("IMPORTE_MAXIMO")));

        Transfer t = service.create(new CreateTransferRequest(ORIGIN, DESTINATION, new BigDecimal("20000.00"), null), ANA);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(t.getRejectionReason()).isEqualTo("FRAUDE");
        verify(accounts, never()).debit(any(), any(), any(), any());
        verify(accounts, never()).credit(any(), any(), any(), any());
        verify(transfers, times(1)).save(t);
    }

    @Test
    void approveExecutesThePendingTransferOnce() {
        Transfer pending = pendingTransfer(new BigDecimal("6000.00"));
        when(transfers.findByIdForUpdate(7L)).thenReturn(Optional.of(pending));

        Transfer resolved = service.approve(7L, GESTOR);

        assertThat(resolved.getStatus()).isEqualTo(TransferStatus.COMPLETADA);
        verify(accounts, times(1)).debit(anyString(), eq(ORIGIN), eq(new BigDecimal("6000.00")), any());
        verify(accounts, times(1)).credit(anyString(), eq(DESTINATION), eq(new BigDecimal("6000.00")), any());
    }

    @Test
    void approveRejectsTheTransferWhenTheLedgerHasNoFunds() {
        Transfer pending = pendingTransfer(new BigDecimal("6000.00"));
        when(transfers.findByIdForUpdate(7L)).thenReturn(Optional.of(pending));
        when(accounts.debit(anyString(), eq(ORIGIN), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "FONDOS_INSUFICIENTES", "Saldo insuficiente"));

        Transfer resolved = service.approve(7L, GESTOR);

        assertThat(resolved.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(resolved.getRejectionReason()).isEqualTo("FONDOS_INSUFICIENTES");
        verify(accounts, times(1)).debit(anyString(), eq(ORIGIN), any(), any());
        verify(accounts, never()).credit(any(), any(), any(), any());
    }

    @Test
    void rejectDoesNotTouchTheLedger() {
        Transfer pending = pendingTransfer(new BigDecimal("6000.00"));
        when(transfers.findByIdForUpdate(7L)).thenReturn(Optional.of(pending));

        Transfer resolved = service.reject(7L, "SOSPECHOSA", GESTOR);

        assertThat(resolved.getStatus()).isEqualTo(TransferStatus.RECHAZADA);
        assertThat(resolved.getRejectionReason()).isEqualTo("SOSPECHOSA");
        verify(accounts, never()).debit(any(), any(), any(), any());
        verify(accounts, never()).credit(any(), any(), any(), any());
    }

    @Test
    void rejectWithoutReasonUsesTheDefault() {
        Transfer pending = pendingTransfer(new BigDecimal("6000.00"));
        when(transfers.findByIdForUpdate(7L)).thenReturn(Optional.of(pending));

        Transfer resolved = service.reject(7L, null, GESTOR);

        assertThat(resolved.getRejectionReason()).isEqualTo("RECHAZADA_POR_GESTOR");
    }

    @Test
    void resolvingATransferThatIsNotPendingConflicts() {
        Transfer done = new Transfer(ORIGIN, DESTINATION, new BigDecimal("100.00"), null, ANA.id());
        when(transfers.findByIdForUpdate(7L)).thenReturn(Optional.of(done));

        assertThatThrownBy(() -> service.approve(7L, GESTOR))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("TRANSFERENCIA_YA_RESUELTA");
                });
        assertThatThrownBy(() -> service.reject(7L, "X", GESTOR))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TRANSFERENCIA_YA_RESUELTA"));
        verify(accounts, never()).debit(any(), any(), any(), any());
    }

    @Test
    void approveUnknownTransferIsNotFound() {
        when(transfers.findByIdForUpdate(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(7L, GESTOR))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getCode()).isEqualTo("TRANSFERENCIA_NO_ENCONTRADA");
                });
    }

    @Test
    void onlyGestoresResolveTransfers() {
        assertThatThrownBy(() -> service.approve(7L, ANA))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.reject(7L, null, ANA))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(transfers, never()).findByIdForUpdate(any());
    }

    @Test
    void listsPendingTransfersForGestores() {
        Transfer pending = pendingTransfer(new BigDecimal("6000.00"));
        when(transfers.findByStatus(eq(TransferStatus.PENDIENTE_REVISION), any()))
                .thenReturn(new PageImpl<>(List.of(pending), PageRequest.of(0, 20), 1));

        PageResponse<TransferResponse> page = service.listPending(0, 20, GESTOR);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).status()).isEqualTo(TransferStatus.PENDIENTE_REVISION);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void clientesCannotListThePendingQueue() {
        assertThatThrownBy(() -> service.listPending(0, 20, ANA))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(transfers, never()).findByStatus(any(), any());
    }

    @Test
    void gestorCanReadAnyTransfer() {
        Transfer t = new Transfer(ORIGIN, DESTINATION, BigDecimal.TEN, null, ANA.id());
        when(transfers.findById(5L)).thenReturn(Optional.of(t));

        assertThat(service.get(5L, GESTOR)).isSameAs(t);
    }

    @Test
    void clienteCannotReadAnotherUsersTransfer() {
        Transfer t = new Transfer(ORIGIN, DESTINATION, BigDecimal.TEN, null, 99L);
        when(transfers.findById(5L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.get(5L, ANA))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private Transfer pendingTransfer(BigDecimal amount) {
        Transfer transfer = new Transfer(ORIGIN, DESTINATION, amount, "Coche", ANA.id());
        transfer.markPendingReview();
        return transfer;
    }
}
