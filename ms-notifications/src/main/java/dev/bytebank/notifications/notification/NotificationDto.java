package dev.bytebank.notifications.notification;

import java.math.BigDecimal;
import java.time.Instant;

public record NotificationDto(
        Long id,
        String iban,
        String type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String reference,
        Long movementId,
        String message,
        Instant createdAt
) {

    public static NotificationDto from(Notification n) {
        return new NotificationDto(n.getId(), n.getIban(), n.getType(), n.getAmount(), n.getBalanceAfter(),
                n.getReference(), n.getMovementId(), n.getMessage(), n.getCreatedAt());
    }
}
