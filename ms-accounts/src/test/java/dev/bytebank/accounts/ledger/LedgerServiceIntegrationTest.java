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

import java.math.BigDecimal;

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
        account.credit(new BigDecimal("100.00"));
        iban = accounts.save(account).getIban();
    }

    @Test
    void debitReducesBalance() {
        ledger.debit(new MovementRequest("TRF-1", iban, 30, MovementType.TRANSFERENCIA_EMITIDA, null));

        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualByComparingTo("70");
    }

    @Test
    void creditIncreasesBalance() {
        ledger.credit(new MovementRequest("TRF-2", iban, 50, MovementType.TRANSFERENCIA_RECIBIDA, null));

        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualByComparingTo("150");
    }

    @Test
    void balanceIsExactWithFractionalCredits() {
        // Regresión del bug: 0,10 + 0,20 en coma flotante daba 0,30000000000000004
        ledger.credit(new MovementRequest("R-1", iban, 0.10, MovementType.INGRESO, null));
        ledger.credit(new MovementRequest("R-2", iban, 0.20, MovementType.INGRESO, null));

        // el setUp ya abona 100.00: 100.00 + 0.10 + 0.20
        assertThat(accounts.findByIban(iban).orElseThrow().getBalance()).isEqualByComparingTo("100.30");
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
