package dev.bytebank.accounts.account;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record AccountResponse(String iban, AccountStatus status, double balance, Long ownerId, Instant createdAt) {

        public static AccountResponse from(Account account) {
            return new AccountResponse(account.getIban(), account.getStatus(), account.getBalance(),
                    account.getOwnerId(), account.getCreatedAt());
        }
    }

    public record ChangeStatusRequest(@NotNull AccountStatus status) {
    }

    public record DepositRequest(@Positive double amount, @Size(max = 140) String concept) {
    }
}
