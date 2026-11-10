package dev.bytebank.notifications.notification;

import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    static final AuthUser ANA = new AuthUser(2L, "ana@bytebank.test", Role.CLIENTE);

    @Mock
    NotificationRepository notifications;

    @InjectMocks
    NotificationService service;

    @Test
    void listsOnlyNotificationsOfTheCaller() {
        Notification n = new Notification(2L, 154L, "ES7639990001000000000001", "TRANSFERENCIA_EMITIDA",
                new BigDecimal("-50.00"), new BigDecimal("2450.00"), "TRF-1", "Has enviado 50,00 €", Instant.now());
        when(notifications.findByUserIdOrderByCreatedAtDescIdDesc(2L, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(n), PageRequest.of(0, 20), 1));

        var result = service.list(0, 20, ANA);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).movementId()).isEqualTo(154L);
        assertThat(result.totalElements()).isEqualTo(1);
    }
}
