package dev.bytebank.notifications.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Evento que publica ms-accounts por cada movimiento. Contrato en docs/contratos/ms-notifications.md. */
public record MovementEvent(Long movementId, String iban, Long ownerId, String type, BigDecimal amount,
                            BigDecimal balanceAfter, String concept, String reference, Instant createdAt,
                            List<Long> holderIds) {

    public static final String TOPIC = "bytebank.movements";

    public MovementEvent(Long movementId, String iban, Long ownerId, String type, BigDecimal amount,
                         BigDecimal balanceAfter, String concept, String reference, Instant createdAt) {
        this(movementId, iban, ownerId, type, amount, balanceAfter, concept, reference, createdAt, List.of(ownerId));
    }
}
