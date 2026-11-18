package dev.bytebank.common.security;

import io.jsonwebtoken.security.Keys;
import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Configuración de firma y validación de los JWT (HS256).
 */
@ConfigurationProperties(prefix = "bytebank.security.jwt")
public record JwtProperties(String secret, long expiration) {

    /** HS256 exige al menos 256 bits: 32 bytes. */
    private static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "bytebank.security.jwt.secret no está definida (defina la variable de entorno JWT_SECRET)");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("bytebank.security.jwt.secret debe tener al menos "
                    + MIN_SECRET_BYTES + " bytes (256 bits) para HS256");
        }
        if (expiration <= 0) {
            throw new IllegalArgumentException("bytebank.security.jwt.expiration debe ser mayor que cero");
        }
    }

    /** Construye la clave HS256 a partir del secreto configurado. */
    public SecretKey secretKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** Evita exponer la clave en logs o trazas. */
    @Override
    public String toString() {
        return "JwtProperties[secret=***, expiration=" + expiration + "]";
    }
}
