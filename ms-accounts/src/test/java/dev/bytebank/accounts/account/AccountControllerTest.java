package dev.bytebank.accounts.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.math.BigDecimal;
import java.util.Map;

import dev.bytebank.accounts.movement.MovementService;
import dev.bytebank.common.CommonAutoConfiguration;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.JwtProperties;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(AccountController.class)
@Import({CommonAutoConfiguration.class, RestClientAutoConfiguration.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "bytebank.internal-key=test-internal-key")
class AccountControllerTest {

    @Autowired
    JwtProperties jwtProperties;

    String tokenCliente;
    String tokenGestor;

    @BeforeEach
    void firmarTokens() {
        tokenCliente = token("1", "cliente@bytebank.test", "CLIENTE");
        tokenGestor = token("2", "gestor@bytebank.test", "GESTOR");
    }

    private String token(String id, String email, String role) {
        return "Bearer " + Jwts.builder()
                .subject(id)
                .claim("email", email)
                .claim("role", role)
                .signWith(jwtProperties.secretKey())
                .compact();
    }

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
                        .header("Authorization", tokenCliente))
                .andExpect(status().isNotFound());
    }

    // GET /api/accounts/{iban}/movements: si la cuenta no existe o no es del cliente, responde 404
    @Test
    void movementsInexistentIbanReturns404() throws Exception {
        when(accountService.get(any(), anyString()))
                .thenThrow(ApiException.notFound("CUENTA_NO_ENCONTRADA", "Cuenta no encontrada"));

        mvc.perform(get("/api/accounts/ES0000000000000000000000/movements")
                        .header("Authorization", tokenCliente))
                .andExpect(status().isNotFound());
    }

    // PATCH /status: un CLIENTE no puede cambiar el estado de una cuenta (403)
    @Test
    void changeStatusAsClienteReturns403() throws Exception {
        mvc.perform(patch("/api/accounts/ES0000000000000000000000/status")
                        .header("Authorization", tokenCliente)
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
                        .header("Authorization", tokenGestor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOQUEADA\"}"))
                .andExpect(status().isOk());
    }

    // POST /deposits: un CLIENTE no puede hacer ingresos en ventanilla (403)
    @Test
    void depositAsClienteReturns403() throws Exception {
        mvc.perform(post("/api/accounts/ES0000000000000000000000/deposits")
                        .header("Authorization", tokenCliente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.0,\"concept\":\"Ingreso ventanilla\"}"))
                .andExpect(status().isForbidden());
    }

    // POST /deposits: un GESTOR sí puede hacer ingresos en ventanilla (200)
    @Test
    void depositAsGestorReturns200() throws Exception {
        String iban = "ES0000000000000000000000";
        when(accountService.deposit(anyString(), any(BigDecimal.class), anyString()))
                .thenReturn(new Account(iban, 1L));

        mvc.perform(post("/api/accounts/{iban}/deposits", iban)
                        .header("Authorization", tokenGestor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100.0,\"concept\":\"Ingreso ventanilla\"}"))
                .andExpect(status().isOk());
    }

    // POST /deposits: la respuesta conserva la escala, "balance": 0.30 y no 0.3
    @Test
    void depositResponseKeepsScaleTwo() throws Exception {
        String iban = Iban.generate(1);
        Account account = new Account(iban, 1L);
        account.credit(new BigDecimal("0.10"));
        account.credit(new BigDecimal("0.20"));
        when(accountService.deposit(eq(iban), any(BigDecimal.class), any()))
                .thenReturn(account);

        String body = mvc.perform(post("/api/accounts/{iban}/deposits", iban)
                        .header("Authorization", tokenGestor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":0.20,\"concept\":\"x\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        Map<String, Object> json = mapper.readValue(body, new TypeReference<>() {});
        assertThat((BigDecimal) json.get("balance")).isEqualTo(new BigDecimal("0.30"));

        // y el texto crudo, que es lo que ve el cliente
        assertThat(body).contains("\"balance\":0.30");
    }

    // POST /deposits: un importe con más de 2 decimales responde 400
    @Test
    void depositWithMoreThanTwoDecimalsReturns400() throws Exception {
        mvc.perform(post("/api/accounts/{iban}/deposits", Iban.generate(1))
                        .header("Authorization", tokenGestor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.005}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));
        verifyNoInteractions(accountService);
    }

    // POST /deposits: un importe enorme responde 400
    @Test
    void depositWithHugeAmountReturns400() throws Exception {
        mvc.perform(post("/api/accounts/{iban}/deposits", Iban.generate(1))
                        .header("Authorization", tokenGestor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1e30}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));
        verifyNoInteractions(accountService);
    }

    // POST /deposits: "Infinity" responde 400
    @Test
    void depositWithInfinityReturns400() throws Exception {
        mvc.perform(post("/api/accounts/{iban}/deposits", Iban.generate(1))
                        .header("Authorization", tokenGestor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"Infinity\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));
        verifyNoInteractions(accountService);
    }

}
