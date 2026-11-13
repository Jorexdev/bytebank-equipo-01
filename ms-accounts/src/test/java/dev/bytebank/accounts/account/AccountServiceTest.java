package dev.bytebank.accounts.account;

import dev.bytebank.accounts.event.MovementPublisher;
import dev.bytebank.accounts.movement.MovementRepository;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    AccountRepository accounts;

    @Mock
    MovementRepository movements;

    @Mock
    MovementPublisher publisher;

    @InjectMocks
    AccountService service;

    // getAccountByIban: lanza 404 si el IBAN no existe
    @Test
    void getAccountByIbanThrowsNotFoundWhenIbanDoesNotExist() {
        String iban = Iban.generate(999);
        when(accounts.findByIban(iban)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccountByIban(iban))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // get: un CLIENTE obtiene la cuenta si es el titular
    @Test
    void getReturnsAccountWhenUserIsOwner() {
        String iban = Iban.generate(1);
        Long ownerId = 2L;
        AuthUser user = new AuthUser(ownerId, "cliente@bytebank.test", Role.CLIENTE);

        Account account = new Account(iban, ownerId);
        when(accounts.findByIbanAndOwnerId(iban, ownerId)).thenReturn(Optional.of(account));

        Account result = service.get(user, iban);

        assertThat(result.getIban()).isEqualTo(iban);
        assertThat(result.getOwnerId()).isEqualTo(ownerId);
    }

    // get: un CLIENTE que no es titular recibe 404 CUENTA_NO_ENCONTRADA
    @Test
    void getThrowsNotFoundWhenUserIsNotOwner() {
        String iban = Iban.generate(2);
        Long ownerId = 2L;
        AuthUser user = new AuthUser(ownerId, "cliente@bytebank.test", Role.CLIENTE);

        when(accounts.findByIbanAndOwnerId(iban, ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(user, iban))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getCode()).isEqualTo("CUENTA_NO_ENCONTRADA");
                });
        verify(accounts).findByIbanAndOwnerId(iban, ownerId);
    }

    // getAccountByIban: sin usuario autenticado (uso interno, evita el 401 en llamadas internas)
    @Test
    void getAccountByIbanWorksWithoutAuthenticatedUser() {
        String iban = Iban.generate(1);
        Long ownerId = 2L;
        Account account = new Account(iban, ownerId);
        when(accounts.findByIban(iban)).thenReturn(Optional.of(account));

        Account result = service.getAccountByIban(iban);
        assertThat(result.getIban()).isEqualTo(iban);
        assertThat(result.getOwnerId()).isEqualTo(ownerId);
        verify(accounts).findByIban(iban);
    }

    /*
     * cuando gestor consulta /api/accounts/{iban}/movements
     * get: GESTOR obtiene cualquier cuenta sin filtrar por titular
     */
    @Test
    void getReturnsAccountWhenUserIsGestor() {
        String iban = Iban.generate(3);
        Long ownerId = 99L;
        Long gestorId = 1L;
        AuthUser gestor = new AuthUser(gestorId, "gestor@bytebank.test", Role.GESTOR);
        Account account = new Account(iban, ownerId);
        when(accounts.findByIban(iban)).thenReturn(Optional.of(account));

        Account result = service.get(gestor, iban);
        assertThat(result.getIban()).isEqualTo(iban);
        assertThat(result.getOwnerId()).isEqualTo(ownerId);
    }
}
