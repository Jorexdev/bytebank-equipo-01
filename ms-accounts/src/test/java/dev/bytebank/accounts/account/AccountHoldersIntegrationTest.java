package dev.bytebank.accounts.account;

import dev.bytebank.accounts.IntegrationTest;
import dev.bytebank.accounts.holder.AccountHolder;
import dev.bytebank.accounts.holder.AccountHolderRepository;
import dev.bytebank.common.iban.Iban;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountHoldersIntegrationTest extends IntegrationTest {

    @Autowired
    AccountRepository accounts;

    @Autowired
    AccountHolderRepository holders;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from movements");
        jdbc.update("delete from accounts");
    }

    @Test
    void accountIsVisibleToEveryHolderButNotToOutsiders() {
        Account account = accounts.save(new Account(Iban.generate(accounts.nextAccountNumber()), 1L));
        holders.save(new AccountHolder(account.getId(), 1L));
        holders.save(new AccountHolder(account.getId(), 9L));

        assertThat(accounts.findByIbanAndHolderId(account.getIban(), 1L)).isPresent();
        assertThat(accounts.findByIbanAndHolderId(account.getIban(), 9L)).isPresent();
        assertThat(accounts.findByIbanAndHolderId(account.getIban(), 99L)).isEmpty();
        assertThat(accounts.findByHolderIdOrderByCreatedAt(9L)).extracting(Account::getIban)
                .containsExactly(account.getIban());
        assertThat(holders.findUserIdsByAccountId(account.getId())).containsExactly(1L, 9L);
        assertThat(holders.countByAccountId(account.getId())).isEqualTo(2L);
    }

    @Test
    void duplicateHolderIsRejectedByUniqueConstraint() {
        Account account = accounts.save(new Account(Iban.generate(accounts.nextAccountNumber()), 1L));
        holders.save(new AccountHolder(account.getId(), 1L));

        assertThatThrownBy(() -> holders.saveAndFlush(new AccountHolder(account.getId(), 1L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
