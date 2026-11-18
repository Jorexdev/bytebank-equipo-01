package dev.bytebank.accounts.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import dev.bytebank.accounts.account.AccountService;
import dev.bytebank.accounts.ledger.LedgerDtos.LedgerResult;
import dev.bytebank.accounts.ledger.LedgerService;
import dev.bytebank.common.CommonAutoConfiguration;
import dev.bytebank.common.security.InternalKeyFilter;
import dev.bytebank.common.security.JwtProperties;
import io.jsonwebtoken.Jwts;

@WebMvcTest(InternalAccountController.class)
@Import({CommonAutoConfiguration.class, RestClientAutoConfiguration.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "bytebank.internal-key=test-internal-key")
class InternalAccountControllerTest {

    static final String INTERNAL_KEY = "test-internal-key";

    @Autowired
    JwtProperties jwtProperties;

    String tokenCliente;

    @BeforeEach
    void firmarToken() {
        tokenCliente = "Bearer " + Jwts.builder()
                .subject("1")
                .claim("email", "cliente@bytebank.test")
                .claim("role", "CLIENTE")
                .signWith(jwtProperties.secretKey())
                .compact();
    }

    @Autowired
    MockMvc mvc;

    @MockBean
    AccountService accountService;

    @MockBean
    LedgerService ledgerService;

    static String body(String type, String amount) {
        return "{\"reference\":\"TRF-1\",\"iban\":\"ES9139990001000000000001\","
                + "\"type\":\"" + type + "\",\"amount\":" + amount + "}";
    }

    static String debitBody(String amount) {
        return body("TRANSFERENCIA_EMITIDA", amount);
    }

    static String creditBody(String amount) {
        return body("TRANSFERENCIA_RECIBIDA", amount);
    }

    // POST /debit: importes inválidos (enormes, cero, negativos, decimales de más, no numéricos) responden 400
    @ParameterizedTest
    @ValueSource(strings = {"1e400", "1e300", "100000000000000000", "0", "-5", "1.234",
            "\"Infinity\"", "\"NaN\"", "\"abc\"", "null"})
    void debitInvalidAmountReturns400(String amount) throws Exception {
        mvc.perform(post("/internal/accounts/debit")
                        .header(InternalKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(debitBody(amount)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));

        verifyNoInteractions(ledgerService);
    }

    // POST /credit: importes inválidos responden 400
    @ParameterizedTest
    @ValueSource(strings = {"1e400", "1e300", "100000000000000000", "0", "-5", "1.234",
            "\"Infinity\"", "\"NaN\"", "\"abc\"", "null"})
    void creditInvalidAmountReturns400(String amount) throws Exception {
        mvc.perform(post("/internal/accounts/credit")
                        .header(InternalKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creditBody(amount)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));

        verifyNoInteractions(ledgerService);
    }

    // POST /debit: sin amount responde 400
    @Test
    void debitMissingAmountReturns400() throws Exception {
        mvc.perform(post("/internal/accounts/debit")
                        .header(InternalKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"TRF-1\",\"iban\":\"ES9139990001000000000001\","
                                + "\"type\":\"TRANSFERENCIA_EMITIDA\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ledgerService);
    }

    // POST /credit: sin amount responde 400
    @Test
    void creditMissingAmountReturns400() throws Exception {
        mvc.perform(post("/internal/accounts/credit")
                        .header(InternalKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"TRF-1\",\"iban\":\"ES9139990001000000000001\","
                                + "\"type\":\"TRANSFERENCIA_RECIBIDA\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(ledgerService);
    }

    // POST /debit: los límites válidos (0.01 y el máximo de NUMERIC(19,2)) responden 200
    @ParameterizedTest
    @ValueSource(strings = {"0.01", "99999999999999999.99"})
    void debitBoundaryAmountReturns200(String amount) throws Exception {
        when(ledgerService.debit(any()))
                .thenReturn(new LedgerResult("TRF-1", new BigDecimal("10.00")));

        mvc.perform(post("/internal/accounts/debit")
                        .header(InternalKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(debitBody(amount)))
                .andExpect(status().isOk());
    }

    // POST /credit: los límites válidos responden 200
    @ParameterizedTest
    @ValueSource(strings = {"0.01", "99999999999999999.99"})
    void creditBoundaryAmountReturns200(String amount) throws Exception {
        when(ledgerService.credit(any()))
                .thenReturn(new LedgerResult("TRF-1", new BigDecimal("10.00")));

        mvc.perform(post("/internal/accounts/credit")
                        .header(InternalKeyFilter.HEADER, INTERNAL_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creditBody(amount)))
                .andExpect(status().isOk());
    }

    // POST /debit: sin clave interna responde 401
    @Test
    void debitWithoutInternalKeyReturns401() throws Exception {
        mvc.perform(post("/internal/accounts/debit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(debitBody("10.00")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(ledgerService);
    }

    // POST /debit: clave interna incorrecta responde 401
    @Test
    void debitWithWrongInternalKeyReturns401() throws Exception {
        mvc.perform(post("/internal/accounts/debit")
                        .header(InternalKeyFilter.HEADER, "otra-clave")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(debitBody("10.00")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(ledgerService);
    }

    // POST /debit: un JWT de CLIENTE no basta para /internal (403)
    @Test
    void debitWithClienteTokenReturns403() throws Exception {
        mvc.perform(post("/internal/accounts/debit")
                        .header("Authorization", tokenCliente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(debitBody("10.00")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(ledgerService);
    }
}