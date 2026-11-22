package dev.bytebank.accounts.holder;

import dev.bytebank.accounts.account.Account;
import dev.bytebank.accounts.account.AccountRepository;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HolderAdderTest {

    @Mock
    AccountRepository accounts;

    @Mock
    AccountHolderRepository holders;

    @InjectMocks
    HolderAdder service;

    private Account accountWithId(String iban, Long id) {
        Account account = new Account(iban, 1L);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    private void givenAccount(String iban, Account account) {
        when(accounts.findByIban(iban)).thenReturn(Optional.of(account));
        when(accounts.findByIbanForUpdate(iban)).thenReturn(Optional.of(account));
    }

    @Test
    void addsHolderWhenRequesterIsHolder() {
        String iban = Iban.generate(40);
        Account account = accountWithId(iban, 5L);
        givenAccount(iban, account);
        when(holders.existsByAccountIdAndUserId(5L, 1L)).thenReturn(true);
        when(holders.countByAccountId(5L)).thenReturn(1L);

        Account result = service.add(1L, iban, 9L);

        assertThat(result).isSameAs(account);
        verify(holders).save(argThat(h -> h.getAccountId().equals(5L) && h.getUserId().equals(9L)));
    }

    @Test
    void rejectsRequesterWhoIsNotHolderWithoutLocking() {
        String iban = Iban.generate(41);
        when(accounts.findByIban(iban)).thenReturn(Optional.of(accountWithId(iban, 5L)));

        assertThatThrownBy(() -> service.add(3L, iban, 9L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getCode()).isEqualTo("CUENTA_NO_ENCONTRADA");
                });
        verify(accounts, never()).findByIbanForUpdate(anyString());
        verify(holders, never()).save(any());
    }

    @Test
    void rechecksHolderAfterAcquiringLock() {
        String iban = Iban.generate(42);
        givenAccount(iban, accountWithId(iban, 5L));
        when(holders.existsByAccountIdAndUserId(5L, 1L)).thenReturn(true, false);

        assertThatThrownBy(() -> service.add(1L, iban, 9L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("CUENTA_NO_ENCONTRADA");
        verify(accounts).findByIbanForUpdate(iban);
        verify(holders, never()).save(any());
    }

    @Test
    void rejectsDuplicateHolder() {
        String iban = Iban.generate(43);
        givenAccount(iban, accountWithId(iban, 5L));
        when(holders.existsByAccountIdAndUserId(5L, 1L)).thenReturn(true);
        when(holders.existsByAccountIdAndUserId(5L, 9L)).thenReturn(true);

        assertThatThrownBy(() -> service.add(1L, iban, 9L))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("YA_ES_TITULAR");
        verify(holders, never()).save(any());
    }

    @Test
    void rejectsWhenMaxHoldersReached() {
        String iban = Iban.generate(44);
        givenAccount(iban, accountWithId(iban, 5L));
        when(holders.existsByAccountIdAndUserId(5L, 1L)).thenReturn(true);
        when(holders.countByAccountId(5L)).thenReturn(4L);

        assertThatThrownBy(() -> service.add(1L, iban, 9L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("MAXIMO_TITULARES");
                });
        verify(holders, never()).save(any());
    }

    @Test
    void mapsLockFailureToConflict() {
        String iban = Iban.generate(45);
        when(accounts.findByIban(iban)).thenReturn(Optional.of(accountWithId(iban, 5L)));
        when(holders.existsByAccountIdAndUserId(5L, 1L)).thenReturn(true);
        when(accounts.findByIbanForUpdate(iban)).thenThrow(new PessimisticLockingFailureException("timeout"));

        assertThatThrownBy(() -> service.add(1L, iban, 9L))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("CUENTA_OCUPADA");
                });
        verify(holders, never()).save(any());
    }
}
