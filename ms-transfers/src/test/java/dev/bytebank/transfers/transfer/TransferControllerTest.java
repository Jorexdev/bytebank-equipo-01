package dev.bytebank.transfers.transfer;

import dev.bytebank.common.CommonAutoConfiguration;
import dev.bytebank.common.security.JwtProperties;
import dev.bytebank.common.web.PageResponse;
import dev.bytebank.transfers.transfer.TransferDtos.TransferResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
@Import({CommonAutoConfiguration.class, RestClientAutoConfiguration.class})
@TestPropertySource(properties = {
        "bytebank.internal-key=test-internal-key",
        "bytebank.security.jwt.secret=test-jwt-secret-para-pruebas-con-32-bytes-o-mas",
        "bytebank.security.jwt.expiration=1440"
})
class TransferControllerTest {

    @Autowired
    JwtProperties jwtProperties;

    @Autowired
    MockMvc mvc;

    @MockBean
    TransferService transferService;

    String tokenCliente;
    String tokenGestor;

    @BeforeEach
    void firmarTokens() {
        tokenCliente = token("2", "CLIENTE");
        tokenGestor = token("9", "GESTOR");
    }

    @Test
    void clienteCannotSeeThePendingQueue() throws Exception {
        mvc.perform(get("/api/transfers/pending").header("Authorization", tokenCliente))
                .andExpect(status().isForbidden());
        verifyNoInteractions(transferService);
    }

    @Test
    void clienteCannotApproveNorReject() throws Exception {
        mvc.perform(post("/api/transfers/7/approve").header("Authorization", tokenCliente))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/transfers/7/reject").header("Authorization", tokenCliente)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"SOSPECHOSA\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(transferService);
    }

    @Test
    void gestorSeesThePendingQueue() throws Exception {
        when(transferService.listPending(eq(0), eq(20), any()))
                .thenReturn(new PageResponse<>(List.of(response(TransferStatus.PENDIENTE_REVISION)), 0, 20, 1, 1));

        mvc.perform(get("/api/transfers/pending?page=0&size=20").header("Authorization", tokenGestor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].status").value("PENDIENTE_REVISION"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void gestorApprovesATransfer() throws Exception {
        when(transferService.approve(eq(7L), any())).thenReturn(transfer(TransferStatus.COMPLETADA));

        send(post("/api/transfers/7/approve"), tokenGestor)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETADA"));
    }

    @Test
    void gestorRejectsATransferWithReason() throws Exception {
        when(transferService.reject(eq(7L), eq("SOSPECHOSA"), any())).thenReturn(transfer(TransferStatus.RECHAZADA));

        send(post("/api/transfers/7/reject"), tokenGestor, "{\"reason\":\"SOSPECHOSA\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECHAZADA"));
    }

    @Test
    void gestorRejectsWithoutBody() throws Exception {
        when(transferService.reject(eq(7L), isNull(), any())).thenReturn(transfer(TransferStatus.RECHAZADA));

        mvc.perform(post("/api/transfers/7/reject").header("Authorization", tokenGestor))
                .andExpect(status().isOk());
    }

    @Test
    void rejectReasonLongerThanFortyCharsIs400() throws Exception {
        send(post("/api/transfers/7/reject"), tokenGestor, "{\"reason\":\"" + "x".repeat(41) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"));
        verifyNoInteractions(transferService);
    }

    @Test
    void negativeAmountIs400() throws Exception {
        send(post("/api/transfers"), tokenCliente,
                "{\"originIban\":\"ES0000000000000000000001\","
                        + "\"destinationIban\":\"ES0000000000000000000002\",\"amount\":-50}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DATOS_NO_VALIDOS"))
                .andExpect(jsonPath("$.fields.amount").exists());
        verifyNoInteractions(transferService);
    }

    @Test
    void withoutTokenIs401() throws Exception {
        mvc.perform(get("/api/transfers/pending")).andExpect(status().isUnauthorized());
        verifyNoInteractions(transferService);
    }

    private org.springframework.test.web.servlet.ResultActions send(MockHttpServletRequestBuilder request,
                                                                    String token) throws Exception {
        return mvc.perform(request.header("Authorization", token));
    }

    private org.springframework.test.web.servlet.ResultActions send(MockHttpServletRequestBuilder request,
                                                                    String token, String body) throws Exception {
        return mvc.perform(request.header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private TransferResponse response(TransferStatus status) {
        return new TransferResponse(7L, "ES0000000000000000000001", "ES0000000000000000000002",
                new BigDecimal("6000.00"), "Coche", status,
                status == TransferStatus.RECHAZADA ? "RECHAZADA_POR_GESTOR" : null,
                Instant.parse("2026-06-12T10:05:00Z"));
    }

    private Transfer transfer(TransferStatus status) {
        Transfer transfer = new Transfer("ES0000000000000000000001", "ES0000000000000000000002",
                new BigDecimal("6000.00"), "Coche", 2L);
        if (status == TransferStatus.PENDIENTE_REVISION) {
            transfer.markPendingReview();
        } else if (status == TransferStatus.RECHAZADA) {
            transfer.reject("RECHAZADA_POR_GESTOR");
        }
        return transfer;
    }

    private String token(String id, String role) {
        return "Bearer " + Jwts.builder().subject(id).claim("email", id + "@bytebank.test").claim("role", role)
                .signWith(Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8))).compact();
    }
}
