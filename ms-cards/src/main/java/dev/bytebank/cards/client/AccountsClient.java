package dev.bytebank.cards.client;

import dev.bytebank.common.http.InternalClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

@Component
public class AccountsClient {

    private final RestClient client;

    public AccountsClient(InternalClients clients, @Value("${bytebank.services.accounts-url}") String accountsUrl) {
        this.client = clients.forService(accountsUrl);
    }

    public record AccountInfo(String iban, String status, BigDecimal balance, Long ownerId) {

        public boolean isActive() {
            return "ACTIVA".equals(status);
        }
    }

    public AccountInfo getAccount(String iban) {
        return InternalClients.call(() -> client.get()
                .uri("/internal/accounts/{iban}", iban)
                .retrieve()
                .body(AccountInfo.class));
    }
}
