package dev.bytebank.fraud;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Umbrales de las reglas antifraude.
 *
 * @param blockThreshold          límite por operación
 * @param externalReviewThreshold importe a partir del cual una transferencia a otra entidad va a revisión
 * @param maxTransfers            número máximo de transferencias aprobadas desde una misma cuenta dentro de la ventana; la siguiente se bloquea
 *@param frequencyWindow          ventana de tiempo en la que se cuentan las transferencias para la regla de frecuencia
 */
@Validated
@ConfigurationProperties(prefix = "bytebank.fraud")
public record FraudProperties(@NotNull @Positive BigDecimal blockThreshold,
                              @NotNull @Positive BigDecimal externalReviewThreshold,
                              @Positive int maxTransfers,
                              @NotNull Duration frequencyWindow) {
}
