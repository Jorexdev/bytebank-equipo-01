package dev.bytebank.accounts.account;

import dev.bytebank.accounts.event.MovementPublisher;
import dev.bytebank.accounts.movement.MovementRepository;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void findByIbanThrowsNotFoundWhenIbanDoesNotExist() {
        String iban = Iban.generate(999);
        when(accounts.findByIban(iban)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByIban(iban))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getReturnsAccountWhenUserIsOwner() {
        String iban = Iban.generate(1);
        Long ownerId = 2L;
        AuthUser user = new AuthUser(ownerId, "cliente@bytebank.test", Role.CLIENTE);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_CLIENTE")))
        );

        Account account = new Account(iban, ownerId);
        when(accounts.findByIbanAndOwnerId(iban, ownerId)).thenReturn(Optional.of(account));

        Account result = service.get(iban);

        assertThat(result.getIban()).isEqualTo(iban);
        assertThat(result.getOwnerId()).isEqualTo(ownerId);
    }

    @Test
    void getThrowsCuentaNoPropiaWhenUserIsNotOwner() {
        String iban = Iban.generate(2);
        Long ownerId = 2L;
        AuthUser user = new AuthUser(ownerId, "cliente@bytebank.test", Role.CLIENTE);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_CLIENTE")))
        );

        when(accounts.findByIbanAndOwnerId(iban, ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(iban))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(ex.getCode()).isEqualTo("CUENTA_NO_PROPIA");
                });
    }
}
