package dev.bytebank.accounts.event;

import dev.bytebank.accounts.account.Account;
import dev.bytebank.accounts.movement.Movement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Evento que se publica en Kafka por cada movimiento. Contrato en docs/contratos/ms-notifications.md. */
public record MovementEvent(Long movementId, String iban, Long ownerId, String type, BigDecimal amount,
                            BigDecimal balanceAfter, String concept, String reference, Instant createdAt,
                            List<Long> holderIds) {

    public static final String TOPIC = "bytebank.movements";

    public MovementEvent(Long movementId, String iban, Long ownerId, String type, BigDecimal amount,
                         BigDecimal balanceAfter, String concept, String reference, Instant createdAt) {
        this(movementId, iban, ownerId, type, amount, balanceAfter, concept, reference, createdAt, List.of(ownerId));
    }

    public static MovementEvent of(Movement movement, Account account, List<Long> holderIds) {
        List<Long> recipients = holderIds == null || holderIds.isEmpty()
                ? List.of(account.getOwnerId()) : holderIds;
        return new MovementEvent(movement.getId(), account.getIban(), account.getOwnerId(), movement.getType().name(),
                movement.getAmount(), movement.getBalanceAfter(), movement.getConcept(), movement.getReference(),
                movement.getCreatedAt(), recipients);
    }
}
