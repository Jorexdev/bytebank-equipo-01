package dev.bytebank.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuración común de los servicios.
 */
@ConfigurationProperties(prefix = "bytebank")
public record BytebankProperties(String internalKey, Security security) {

    public BytebankProperties {
        if (security == null) {
            security = new Security(List.of());
        }
    }

    /** Rutas públicas propias de cada servicio (por ejemplo, el login en ms-auth). */
    public record Security(List<String> publicPaths) {

        public Security {
            publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
        }
    }
}
