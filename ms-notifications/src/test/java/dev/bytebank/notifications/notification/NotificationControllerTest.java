package dev.bytebank.notifications.notification;

import dev.bytebank.common.CommonAutoConfiguration;
import dev.bytebank.common.security.SecurityConstants;
import dev.bytebank.common.web.PageResponse;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
@Import({CommonAutoConfiguration.class, RestClientAutoConfiguration.class})
class NotificationControllerTest {

    static final String TOKEN = "Bearer " + Jwts.builder()
            .subject("2")
            .claim("email", "ana@bytebank.test")
            .claim("role", "CLIENTE")
            .signWith(Keys.hmacShaKeyFor(SecurityConstants.JWT_SECRET.getBytes(StandardCharsets.UTF_8))).compact();

    @Autowired
    MockMvc mvc;
    @MockBean
    NotificationService service;

    @Test
    void returnsPageOfNotifications() throws Exception {
        var dto = new NotificationDto(31L, "ES7639990001000000000001", "TRANSFERENCIA_EMITIDA", new BigDecimal("-50.00"),
                new BigDecimal("2450.00"), "TRF-1", 154L, "Has enviado 50,00 €", Instant.parse("2026-06-12T10:05:00Z"));
        when(service.list(eq(0), eq(20), any())).thenReturn(new PageResponse<>(List.of(dto), 0, 20, 1, 1));

        mvc.perform(get("/api/notifications").header("Authorization", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].movementId").value(154))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-06-12T10:05:00Z"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void withoutTokenIsUnauthorized()throws Exception {
        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    void sizeZeroIsBadRequest() throws Exception {
        mvc.perform(get("/api/notifications?size=0").header("Authorization", TOKEN)).andExpect(status().isBadRequest());
    }

    @Test
    void sizeAbove100IsBadRequest() throws Exception {
        mvc.perform(get("/api/notifications?size=101").header("Authorization", TOKEN)).andExpect(status().isBadRequest());
    }
}
