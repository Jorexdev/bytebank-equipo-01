package dev.bytebank.accounts.movement;

import dev.bytebank.accounts.IntegrationTest;
import dev.bytebank.accounts.account.Account;
import dev.bytebank.accounts.account.AccountRepository;
import dev.bytebank.common.iban.Iban;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MovementStatementIntegrationTest extends IntegrationTest {

    @Autowired
    MovementService service;
    @Autowired
    AccountRepository accounts;
    @Autowired
    MovementRepository movements;
    @Autowired
    JdbcTemplate jdbc;

    Account account;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from movements");
        jdbc.update("delete from accounts");
        account = accounts.save(new Account(Iban.generate(accounts.nextAccountNumber()), 1L));
    }

    @Test
    void firstPageIncludesTheNewestMovement() {
        for (int i = 0; i < 5; i++) {
            movements.save(new Movement(account, MovementType.INGRESO, 10, "Movement " + i, null));
        }

        var page = service.statement(account, null, null, 0, 20);

        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.content()).hasSize(5);
    }

    @Test
    void paginationHasNoGapsNorDuplicates() {
        for (int i = 0; i < 25; i++) {
            movements.save(new Movement(account, MovementType.INGRESO, 10, "Movement " + i, null));
        }

        var first = service.statement(account, null, null, 0, 20);
        var second = service.statement(account, null, null, 1, 20);

        assertThat(first.content()).hasSize(20);
        assertThat(second.content()).hasSize(5);
        assertThat(Stream.concat(first.content().stream(), second.content().stream())
                .map(MovementResponse::id)
                .toList())
                .hasSize(25)
                .doesNotHaveDuplicates();
    }
}
