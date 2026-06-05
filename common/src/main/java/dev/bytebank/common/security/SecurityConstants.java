package dev.bytebank.common.security;

/**
 * Constantes de seguridad compartidas por ms-auth (que firma los tokens) y el resto de servicios (que los validan).
 */
public final class SecurityConstants {

    public static final String JWT_SECRET = "bytebank-secret-key-que-nadie-va-a-adivinar-2024";

    public static final long JWT_EXPIRATION_MINUTES = 24 * 60;

    private SecurityConstants() {
    }
}
