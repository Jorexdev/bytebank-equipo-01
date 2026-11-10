package dev.bytebank.notifications.notification;

import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.web.PageResponse;
import dev.bytebank.notifications.event.MovementEvent;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;

@Service
public class NotificationService {

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional
    public void notifyOwner(MovementEvent event) {
        notifications.save(new Notification(event.ownerId(), event.movementId(), event.iban(), event.type(),
                BigDecimal.valueOf(event.amount()), BigDecimal.valueOf(event.balanceAfter()), event.reference(),
                NotificationMessages.of(event), event.createdAt()));
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationDto> list(int page, int size, AuthUser user) {
        var result = notifications.findByUserIdOrderByCreatedAtDescIdDesc(user.id(), PageRequest.of(page, size));
        return PageResponse.of(result, NotificationDto::from);
    }
}
