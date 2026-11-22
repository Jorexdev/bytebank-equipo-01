package dev.bytebank.accounts.account;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AccountDtos {

    private AccountDtos() {
    }

    public record AccountResponse(String iban, AccountStatus status, BigDecimal balance, Long ownerId,
                                  Instant createdAt, List<Long> holderIds) {

        public AccountResponse(String iban, AccountStatus status, BigDecimal balance, Long ownerId, Instant createdAt) {
            this(iban, status, balance, ownerId, createdAt, List.of(ownerId));
        }

        public static AccountResponse from(Account account) {
            return from(account, List.of(account.getOwnerId()));
        }

        public static AccountResponse from(Account account, List<Long> holderIds) {
            List<Long> ids = holderIds == null ? List.of(account.getOwnerId()) : List.copyOf(holderIds);
            return new AccountResponse(account.getIban(), account.getStatus(), account.getBalance(),
                    account.getOwnerId(), account.getCreatedAt(), ids);
        }
    }

    public record AddHolderRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record ChangeStatusRequest(@NotNull AccountStatus status) {
    }

    public record DepositRequest(@Positive @Digits(integer = 17, fraction = 2) BigDecimal amount, @Size(max = 140) String concept) {
    }
}
