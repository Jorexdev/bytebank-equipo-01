package dev.bytebank.fraud;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Umbrales de las reglas antifraude.
 *
 * @param blockThreshold          límite por operación
 * @param externalReviewThreshold importe a partir del cual una transferencia a otra entidad va a revisión
 */
@Validated
@ConfigurationProperties(prefix = "bytebank.fraud")
public record FraudProperties(@NotNull @Positive BigDecimal blockThreshold,
                              @NotNull @Positive BigDecimal externalReviewThreshold) {
}
