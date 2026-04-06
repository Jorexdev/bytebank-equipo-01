package dev.bytebank.common.http;

import dev.bytebank.common.error.ApiException;
import org.springframework.http.HttpStatus;

/** El servicio remoto no responde o ha fallado. */
public class RemoteUnavailableException extends ApiException {

    public RemoteUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "SERVICIO_NO_DISPONIBLE", message);
    }
}
