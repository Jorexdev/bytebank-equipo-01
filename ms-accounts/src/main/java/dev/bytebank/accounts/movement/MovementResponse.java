package dev.bytebank.accounts.movement;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Apunte del extracto. {@code createdAt} se devuelve con el desfase de la zona horaria de negocio
 * (no en UTC) para que el cliente pinte el día en el que hizo la operación.
 */
public record MovementResponse(Long id, MovementType type, BigDecimal amount, BigDecimal balanceAfter,
                               String concept, String reference, OffsetDateTime createdAt) {

    public static MovementResponse from(Movement movement, ZoneId zone) {
        return new MovementResponse(movement.getId(), movement.getType(), movement.getAmount(),
                movement.getBalanceAfter(), movement.getConcept(), movement.getReference(),
                movement.getCreatedAt().atZone(zone).toOffsetDateTime());
    }
}
