package dev.bytebank.fraud.check;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class FraudDtos {

    private FraudDtos() {
    }

    public record CheckRequest(@NotBlank String originIban, @NotBlank String destinationIban, @NotNull Long userId,
                               @NotNull @DecimalMin("0.01") BigDecimal amount) {
    }

    public record CheckResponse(Long checkId, Verdict verdict, List<String> reasons) {
    }

    public record CheckView(Long id, String originIban, Long userId, BigDecimal amount, Verdict verdict,
                            List<String> reasons, Instant createdAt) {

        public static CheckView from(FraudCheck check) {
            return new CheckView(check.getId(), check.getOriginIban(), check.getUserId(), check.getAmount(),
                    check.getVerdict(), check.getReasons(), check.getCreatedAt());
        }
    }
}
