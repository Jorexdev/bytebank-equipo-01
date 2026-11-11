package dev.bytebank.notifications.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.bytebank.notifications.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class MovementEventListenerTest {

    @Test
    void ignoresDuplicateMovement() {
        NotificationService service = mock(NotificationService.class);
        doThrow(DataIntegrityViolationException.class).when(service).notifyOwner(any());
        var listener = new MovementEventListener(service, new ObjectMapper().registerModule(new JavaTimeModule()));

        String payload = """
                {"movementId":154,"iban":"ES7639990001000000000001","ownerId":2,"type":"INGRESO",
                 "amount":50.0,"balanceAfter":50.0,"createdAt":"2026-06-12T10:05:00Z"}""";

        assertThatCode(() -> listener.onMovement(payload)).doesNotThrowAnyException();
    }
}
