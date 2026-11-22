package dev.bytebank.accounts.client;

import dev.bytebank.common.http.InternalClients;
import dev.bytebank.common.security.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Consulta de usuarios a ms-auth para resolver el email del nuevo titular. */
@Component
public class AuthClient {

    private final RestClient client;

    public AuthClient(InternalClients clients, @Value("${bytebank.services.auth-url}") String authUrl) {
        this.client = clients.forService(authUrl);
    }

    public record AuthUserResponse(Long id, String email, Role role) {
    }

    public AuthUserResponse findByEmail(String email) {
        return InternalClients.call(() -> client.get()
                .uri(uri -> uri.path("/internal/users/by-email").queryParam("email", email).build())
                .retrieve()
                .body(AuthUserResponse.class));
    }
}
