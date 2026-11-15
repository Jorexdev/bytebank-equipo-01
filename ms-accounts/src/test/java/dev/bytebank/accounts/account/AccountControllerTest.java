package dev.bytebank.accounts.account;

import dev.bytebank.accounts.movement.MovementService;
import dev.bytebank.common.CommonAutoConfiguration;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.security.SecurityConstants;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import({CommonAutoConfiguration.class, RestClientAutoConfiguration.class})
@TestPropertySource(properties = "bytebank.internal-key=test-internal-key")
class AccountControllerTest {

    static final String JWT_SECRET = SecurityConstants.JWT_SECRET;

    static final String TOKEN_CLIENTE = "Bearer " + Jwts.builder()
            .subject("1")
            .claim("email", "cliente@bytebank.test")
            .claim("role", "CLIENTE")
            .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();

    static final String TOKEN_GESTOR = "Bearer " + Jwts.builder()
            .subject("2")
            .claim("email", "gestor@bytebank.test")
            .claim("role", "GESTOR")
            .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();

    @Autowired
    MockMvc mvc;

    @MockBean
    AccountService accountService;

    @MockBean
    MovementService movementService;

    // GET /api/accounts/{iban}: si la cuenta no existe o no es del cliente, responde 404
    @Test
    void getInexistentIbanReturns404() throws Exception {
        when(accountService.get(any(), anyString()))
                .thenThrow(ApiException.notFound("CUENTA_NO_ENCONTRADA", "Cuenta no encontrada"));

        mvc.perform(get("/api/accounts/ES0000000000000000000000")
                        .header("Authorization", TOKEN_CLIENTE))
                .andExpect(status().isNotFound());
    }

    // GET /api/accounts/{iban}/movements: si la cuenta no existe o no es del cliente, responde 404
    @Test
    void movementsInexistentIbanReturns404() throws Exception {
        when(accountService.get(any(), anyString()))
                .thenThrow(ApiException.notFound("CUENTA_NO_ENCONTRADA", "Cuenta no encontrada"));

        mvc.perform(get("/api/accounts/ES0000000000000000000000/movements")
                        .header("Authorization", TOKEN_CLIENTE))
                .andExpect(status().isNotFound());
    }

    // PATCH /status: un CLIENTE no puede cambiar el estado de una cuenta (403)
    @Test
    void changeStatusAsClienteReturns403() throws Exception {
        mvc.perform(patch("/api/accounts/ES0000000000000000000000/status")
                        .header("Authorization", TOKEN_CLIENTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOQUEADA\"}"))
                .andExpect(status().isForbidden());
    }

    // PATCH /status: un GESTOR sí puede cambiar el estado de una cuenta (200)
    @Test
    void changeStatusAsGestorReturns200() throws Exception {
        String iban = "ES0000000000000000000000";
        when(accountService.changeStatus(anyString(), any()))
                .thenReturn(new Account(iban, 1L));

        mvc.perform(patch("/api/accounts/{iban}/status", iban)
                        .header("Authorization", TOKEN_GESTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOQUEADA\"}"))
                .andExpect(status().isOk());
    }

    // POST /deposits: un CLIENTE no puede hacer ingresos en ventanilla (403)
    @Test
    void depositAsClienteReturns403() throws Exception {
        mvc.perform(post("/api/accounts/ES0000000000000000000000/deposits")
                        .header("Authorization", TOKEN_CLIENTE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.0,\"concept\":\"Ingreso ventanilla\"}"))
                .andExpect(status().isForbidden());
    }

    // POST /deposits: un GESTOR sí puede hacer ingresos en ventanilla (200)
    @Test
    void depositAsGestorReturns200() throws Exception {
        String iban = "ES0000000000000000000000";
        when(accountService.deposit(anyString(), anyDouble(), anyString()))
                .thenReturn(new Account(iban, 1L));

        mvc.perform(post("/api/accounts/{iban}/deposits", iban)
                        .header("Authorization", TOKEN_GESTOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.0,\"concept\":\"Ingreso ventanilla\"}"))
                .andExpect(status().isOk());
    }
}
