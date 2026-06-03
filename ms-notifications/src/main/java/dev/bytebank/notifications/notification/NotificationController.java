package dev.bytebank.notifications.notification;

import dev.bytebank.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {

    // TODO: listado paginado de los avisos del usuario (ver docs/contratos/ms-notifications.md)
    @GetMapping("/api/notifications")
    public Object list() {
        throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "NO_IMPLEMENTADO", "Consultar los avisos aún no está disponible");
    }
}
