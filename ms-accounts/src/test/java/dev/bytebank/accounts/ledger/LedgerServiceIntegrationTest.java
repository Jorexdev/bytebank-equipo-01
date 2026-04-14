package dev.bytebank.accounts.ledger;

import dev.bytebank.accounts.IntegrationTest;
import dev.bytebank.accounts.account.Account;
import dev.bytebank.accounts.account.AccountRepository;
import dev.bytebank.accounts.account.AccountStatus;
import dev.bytebank.accounts.ledger.LedgerDtos.MovementRequest;
import dev.bytebank.accounts.movement.MovementType;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServiceIntegrationTest extends IntegrationTest {

    @Autowired
    LedgerService ledger;
    @Autowired
    AccountRepository accounts;
    @Autowired
    JdbcTemplate jdbc;

    String iban;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from movements");
        jdbc.update("delete from accounts");
        Account account = new Account(Iban.generate(accounts.nextAccountNumber()), 1L);
        account.credit(100);
        iban = accounts.save(account).getIban();
    }

    @Test
    void debitReducesBalance() {
        ledger.debit(new MovementRequest("TRF-1", iban, 30, MovementType.TRANSFERENCIA_EMITIDA, null));

        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualTo(70);
    }

    @Test
    void creditIncreasesBalance() {
        ledger.credit(new MovementRequest("TRF-2", iban, 50, MovementType.TRANSFERENCIA_RECIBIDA, null));

        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualTo(150);
    }

    @Test
    void cannotDebitMoreThanBalance() {
        assertThatThrownBy(() -> ledger.debit(new MovementRequest("TRF-3", iban, 101, MovementType.TRANSFERENCIA_EMITIDA, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void cannotCreditBlockedAccount() {
        Account account = accounts.findByIban(iban).orElseThrow();
        account.changeStatus(AccountStatus.BLOQUEADA);
        accounts.save(account);

        assertThatThrownBy(() -> ledger.credit(new MovementRequest("TRF-4", iban, 10, MovementType.TRANSFERENCIA_RECIBIDA, null)))
                .isInstanceOf(ApiException.class);
    }
}
