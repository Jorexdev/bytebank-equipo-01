package dev.bytebank.transfers.client;

import dev.bytebank.common.http.InternalClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

@Component
public class FraudClient {

    private final RestClient client;

    public FraudClient(InternalClients clients, @Value("${bytebank.services.fraud-url}") String fraudUrl) {
        this.client = clients.forService(fraudUrl);
    }

    public record CheckRequest(String originIban, String destinationIban, Long userId, BigDecimal amount) {
    }

    public record CheckResponse(Long checkId, Verdict verdict, List<String> reasons) {
    }

    public CheckResponse check(String originIban, String destinationIban, Long userId, BigDecimal amount) {
        return InternalClients.call(() -> client.post()
                .uri("/internal/fraud/check")
                .body(new CheckRequest(originIban, destinationIban, userId, amount))
                .retrieve()
                .body(CheckResponse.class));
    }
}
