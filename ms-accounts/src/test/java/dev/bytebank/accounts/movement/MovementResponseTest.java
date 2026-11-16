package dev.bytebank.accounts.movement;

import dev.bytebank.accounts.account.Account;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class MovementResponseTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    @Test
    void paymentAtHalfPastMidnightIsShownOnTheDayItWasMade() throws Exception {
        Movement movement = new Movement(new Account("ES9121000418450200051332", 2L),
                MovementType.PAGO_TARJETA, -12.34, "Compra", "REF-1");
        // El cliente pagó a las 00:30 del 15 de noviembre en Madrid (23:30Z del 14)
        Instant pago = LocalDateTime.of(2026, 11, 15, 0, 30).atZone(MADRID).toInstant();
        setCreatedAt(movement, pago);

        MovementResponse response = MovementResponse.from(movement, MADRID);

        assertThat(response.createdAt())
                .isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 30).atZone(MADRID).toOffsetDateTime());
        // el instante no se pierde: sigue siendo el mismo
        assertThat(response.createdAt().toInstant()).isEqualTo(pago);
    }

    private static void setCreatedAt(Movement movement, Instant instant) throws Exception {
        Field field = Movement.class.getDeclaredField("createdAt");
        field.setAccessible(true);
        field.set(movement, instant);
    }
}
