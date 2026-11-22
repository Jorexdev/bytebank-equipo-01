package dev.bytebank.accounts.account;

import dev.bytebank.accounts.client.AuthClient;
import dev.bytebank.accounts.event.MovementPublisher;
import dev.bytebank.accounts.holder.AccountHolder;
import dev.bytebank.accounts.holder.AccountHolderRepository;
import dev.bytebank.accounts.movement.Movement;
import dev.bytebank.accounts.movement.MovementRepository;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    AccountRepository accounts;

    @Mock
    MovementRepository movements;

    @Mock
    MovementPublisher publisher;

    @Mock
    AccountHolderRepository holders;

    @Mock
    AuthClient authClient;

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
        when(accounts.findByIbanAndHolderId(iban, ownerId)).thenReturn(Optional.of(account));

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

        when(accounts.findByIbanAndHolderId(iban, ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(user, iban))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getCode()).isEqualTo("CUENTA_NO_ENCONTRADA");
                });
        verify(accounts).findByIbanAndHolderId(iban, ownerId);
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
    
    // deposit: 0,10 + 0,20 da saldo 0,30 (y no 0,3 ni 0,30000000000000004)
    @Test
    void depositTenCentsPlusTwentyCentsGivesThirtyCents() {
        String iban = Iban.generate(1);
        Account account = new Account(iban, 2L);
        when(accounts.findByIban(iban)).thenReturn(Optional.of(account));
        when(movements.save(any(Movement.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deposit(iban, new BigDecimal("0.10"), "primero");
        Account result = service.deposit(iban, new BigDecimal("0.20"), "segundo");

        // isEqualTo (no isEqualByComparingTo): compara también la escala
        assertThat(result.getBalance()).isEqualTo(new BigDecimal("0.30"));
        verify(movements, times(2)).save(any(Movement.class));
        verify(publisher, times(2)).publish(any(Movement.class), any(Account.class));
    }
    
    // deposit: importes no válidos (cero, negativo, más de 2 decimales) dan 400 IMPORTE_NO_VALIDO
    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5.00", "10.005", "0.001"})
    void depositWithInvalidAmountThrowsBadRequest(String amount) {
        String iban = Iban.generate(1);

        assertThatThrownBy(() -> service.deposit(iban, new BigDecimal(amount), "x"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getCode()).isEqualTo("IMPORTE_NO_VALIDO");
                });
        verifyNoInteractions(accounts, movements, publisher);
    }

    // deposit: importe null da 400 IMPORTE_NO_VALIDO
    @Test
    void depositWithNullAmountThrowsBadRequest() {
        String iban = Iban.generate(1);

        assertThatThrownBy(() -> service.deposit(iban, null, "x"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("IMPORTE_NO_VALIDO"));
        verifyNoInteractions(accounts, movements, publisher);
    }

    // deposit: 0.100 (ceros finales) y 1E+3 son válidos y se guardan con escala 2
    @Test
    void depositAcceptsTrailingZerosAndExponentNotation() {
        String iban = Iban.generate(1);
        Account account = new Account(iban, 2L);
        when(accounts.findByIban(iban)).thenReturn(Optional.of(account));
        when(movements.save(any(Movement.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deposit(iban, new BigDecimal("0.100"), "a");
        Account result = service.deposit(iban, new BigDecimal("1E+3"), "b");

        assertThat(result.getBalance()).isEqualTo(new BigDecimal("1000.10"));
    }

    private Account accountWithId(String iban, Long id) {
        Account account = new Account(iban, 1L);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    private void givenHolderExists(Long accountId, Long userId) {
        when(holders.existsByAccountIdAndUserId(accountId, userId)).thenReturn(true);
    }

    // addHolder: un titular añade a otro cliente por email
    @Test
    void addHolderSavesNewHolder() {
        String iban = Iban.generate(10);
        Account account = accountWithId(iban, 5L);
        AuthUser requester = new AuthUser(1L, "titular@bytebank.test", Role.CLIENTE);
        givenHolderExists(5L, 1L);
        when(accounts.findByIbanForUpdate(iban)).thenReturn(Optional.of(account));
        when(authClient.findByEmail("nuevo@bytebank.test"))
                .thenReturn(new AuthClient.AuthUserResponse(9L, "nuevo@bytebank.test", Role.CLIENTE));
        when(holders.countByAccountId(5L)).thenReturn(1L);

        service.addHolder(requester, iban, " Nuevo@ByteBank.test ");

        verify(holders).save(argThat(h ->
                h.getAccountId().equals(5L) && h.getUserId().equals(9L)));
    }

    // addHolder: quien llama no es titular -> 404
    @Test
    void addHolderRejectsRequesterWhoIsNotHolder() {
        String iban = Iban.generate(11);
        Account account = accountWithId(iban, 5L);
        when(accounts.findByIbanForUpdate(iban)).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.addHolder(new AuthUser(3L, "otro@bytebank.test", Role.CLIENTE),
                iban, "nuevo@bytebank.test"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getCode()).isEqualTo("CUENTA_NO_ENCONTRADA");
                });
        verifyNoInteractions(authClient);
    }

    // addHolder:email inexistente -> se propaga el 404 de ms-auth
    @Test
    void addHolderPropagatesUnknownEmail() {
        String iban = Iban.generate(12);
        Account account = accountWithId(iban, 5L);
        givenHolderExists(5L, 1L);
        when(accounts.findByIbanForUpdate(iban)).thenReturn(Optional.of(account));
        when(authClient.findByEmail(anyString())).thenThrow(
                ApiException.notFound("USUARIO_NO_ENCONTRADO", "No existe un usuario con ese email"));

        assertThatThrownBy(() -> service.addHolder(new AuthUser(1L, "titular@bytebank.test", Role.CLIENTE),
                iban, "nadie@bytebank.test"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("USUARIO_NO_ENCONTRADO");
    }

    // addHolder: solo se puede añadir a un CLIENTE
    @Test
    void addHolderRejectsGestor() {
        String iban = Iban.generate(13);
        Account account = accountWithId(iban, 5L);
        givenHolderExists(5L, 1L);
        when(accounts.findByIbanForUpdate(iban)).thenReturn(Optional.of(account));
        when(authClient.findByEmail(anyString()))
                .thenReturn(new AuthClient.AuthUserResponse(8L, "gestor@bytebank.test", Role.GESTOR));

        assertThatThrownBy(() -> service.addHolder(new AuthUser(1L, "titular@bytebank.test", Role.CLIENTE),
                iban, "gestor@bytebank.test"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getCode()).isEqualTo("TITULAR_NO_VALIDO");
                });
    }

    // addHolder: titular repetido -> 409
    @Test
    void addHolderRejectsDuplicate() {
        String iban = Iban.generate(14);
        Account account = accountWithId(iban, 5L);
        givenHolderExists(5L, 1L);
        when(accounts.findByIbanForUpdate(iban)).thenReturn(Optional.of(account));
        when(authClient.findByEmail(anyString()))
                .thenReturn(new AuthClient.AuthUserResponse(1L, "titular@bytebank.test", Role.CLIENTE));

        assertThatThrownBy(() -> service.addHolder(new AuthUser(1L, "titular@bytebank.test", Role.CLIENTE),
                iban, "titular@bytebank.test"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo("YA_ES_TITULAR");
    }

    // addHolder: máximo 4 titulares
    @Test
    void addHolderRejectsWhenMaxHoldersReached() {
        String iban = Iban.generate(15);
        Account account = accountWithId(iban, 5L);
        givenHolderExists(5L, 1L);
        when(accounts.findByIbanForUpdate(iban)).thenReturn(Optional.of(account));
        when(authClient.findByEmail(anyString()))
                .thenReturn(new AuthClient.AuthUserResponse(9L, "nuevo@bytebank.test", Role.CLIENTE));
        when(holders.countByAccountId(5L)).thenReturn(4L);

        assertThatThrownBy(() -> service.addHolder(new AuthUser(1L, "titular@bytebank.test", Role.CLIENTE),
                iban, "nuevo@bytebank.test"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getCode()).isEqualTo("MAXIMO_TITULARES");
                });
        verify(holders, never()).save(any(AccountHolder.class));
    }

    // listOwn: también devuelve cuentas de las que el usuario es cotitular
    @Test
    void listOwnReturnsAccountsWhereUserIsHolder() {
        AuthUser user = new AuthUser(7L, "cotitular@bytebank.test", Role.CLIENTE);
        when(accounts.findByHolderIdOrderByCreatedAt(7L))
                .thenReturn(List.of(new Account(Iban.generate(20), 1L)));

        assertThat(service.listOwn(user)).hasSize(1);
        verify(accounts).findByHolderIdOrderByCreatedAt(7L);
    }

    // holderIds: se exponen todos los titulares de la cuenta
    @Test
    void holderIdsReturnsAllHolders() {
        Account account = accountWithId(Iban.generate(21), 5L);
        when(holders.findUserIdsByAccountId(5L)).thenReturn(List.of(1L, 9L));

        assertThat(service.holderIds(account)).containsExactly(1L, 9L);
    }

}
