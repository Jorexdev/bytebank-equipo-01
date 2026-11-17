package dev.bytebank.cards.card;

import dev.bytebank.cards.payment.CardPayment;
import dev.bytebank.common.CommonAutoConfiguration;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CardController.class)
@Import({CommonAutoConfiguration.class, RestClientAutoConfiguration.class})
@TestPropertySource(properties = "bytebank.internal-key=test-internal-key")
class CardControllerTest {

    static final String CLIENTE = token("1", "CLIENTE");
    static final String GESTOR = token("2", "GESTOR");

    @Autowired
    MockMvc mvc;
    @MockBean
    CardService cardService;

    @Test
    void issueReturns201() throws Exception {
        when(cardService.issue(anyString(), any())).thenReturn(new Card("ES7639990001000000000001", 1L, "4821", BigDecimal.TEN));

        send("/api/cards", CLIENTE, "{\"iban\":\"ES7639990001000000000001\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maskedNumber").value("**** **** **** 4821"))
                .andExpect(jsonPath("$.status").value("ACTIVA"));
    }

    @Test
    void issueWithoutIbanIs400() throws Exception {
        send("/api/cards", CLIENTE, "{}").andExpect(status().isBadRequest());
        verifyNoInteractions(cardService);
    }

    @Test
    void rejectedPaymentIsStill201() throws Exception {
        var payment = new CardPayment(1L, BigDecimal.TEN, "Tienda", Instant.now());
        payment.reject("LIMITE_DIARIO_SUPERADO");
        when(cardService.pay(any(), any(), anyString(), any())).thenReturn(payment);

        send("/api/cards/1/payments", CLIENTE, "{\"amount\":10,\"merchant\":\"Tienda\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECHAZADO"))
                .andExpect(jsonPath("$.rejectionReason").value("LIMITE_DIARIO_SUPERADO"));
    }

    @Test
    void invalidPaymentsAre400() throws Exception {
        for (String body : new String[]{
                "{\"amount\":0,\"merchant\":\"Tienda\"}",
                "{\"amount\":-5,\"merchant\":\"Tienda\"}",
                "{\"amount\":10.505,\"merchant\":\"Tienda\"}",
                "{\"amount\":10}",
                "{\"merchant\":\"Tienda\"}"}) {
            send("/api/cards/1/payments", CLIENTE, body).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(cardService);
    }

    @Test
    void gestorCannotPayNorIssue() throws Exception {
        send("/api/cards/1/payments", GESTOR, "{\"amount\":10,\"merchant\":\"Tienda\"}").andExpect(status().isForbidden());
        send("/api/cards", GESTOR, "{\"iban\":\"ES7639990001000000000001\"}").andExpect(status().isForbidden());
    }

    @Test
    void withoutTokenIs401() throws Exception {
        send("/api/cards/1/payments", null, "{\"amount\":10,\"merchant\":\"Tienda\"}").andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions send(String url, String token, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(url).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header("Authorization", token);
        }
        return mvc.perform(request);
    }

    private static String token(String id, String role) {
        return "Bearer " + Jwts.builder().subject(id).claim("email", id + "@bytebank.test").claim("role", role)
                .signWith(Keys.hmacShaKeyFor(SecurityConstants.JWT_SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
