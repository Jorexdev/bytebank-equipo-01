package dev.bytebank.accounts.account;

import dev.bytebank.accounts.IntegrationTest;
import dev.bytebank.accounts.client.AuthClient;
import dev.bytebank.accounts.holder.AccountHolder;
import dev.bytebank.accounts.holder.AccountHolderRepository;
import dev.bytebank.accounts.internal.InternalAccountController;
import dev.bytebank.accounts.ledger.LedgerDtos.MovementRequest;
import dev.bytebank.accounts.ledger.LedgerService;
import dev.bytebank.accounts.movement.MovementType;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class AccountHoldersIntegrationTest extends IntegrationTest {

    @Autowired
    AccountRepository accounts;

    @Autowired
    AccountHolderRepository holders;

    @Autowired
    AccountService accountService;

    @Autowired
    LedgerService ledgerService;

    @Autowired
    InternalAccountController internalAccountController;

    @Autowired
    JdbcTemplate jdbc;

    @MockBean
    AuthClient authClient;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from movements");
        jdbc.update("delete from accounts");
    }

    private Account accountWithBalanceAndHolder() {
        Account account = accounts.save(new Account(Iban.generate(accounts.nextAccountNumber()), 1L));
        account.credit(new BigDecimal("10.00"));
        accounts.save(account);
        holders.save(new AccountHolder(account.getId(), 1L));
        return account;
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

    /** El nuevo titular queda expuesto en la respuesta interna que consumen ms-cards y ms-transfers. */
    @Test
    void internalAccountResponseExposesNewHolder() {
        Account account = accountWithBalanceAndHolder();
        when(authClient.findByEmail("nuevo@bytebank.test"))
                .thenReturn(new AuthClient.AuthUserResponse(9L, "nuevo@bytebank.test", Role.CLIENTE));

        accountService.addHolder(new AuthUser(1L, "titular@bytebank.test", Role.CLIENTE),
                account.getIban(), "nuevo@bytebank.test");

        var response = internalAccountController.get(account.getIban());
        assertThat(response.ownerId()).isEqualTo(1L);
        assertThat(response.holderIds()).containsExactly(1L, 9L);
    }

    /**
     * Regresión del riesgo alto: mientras ms-auth está respondiendo, la cuenta NO debe estar bloqueada.
     * Con el código anterior el FOR UPDATE se tomaba antes del HTTP y este cargo fallaba por lock_timeout.
     */
    @Test
    void debitIsNotBlockedWhileEmailIsBeingResolved() throws Exception {
        Account account = accountWithBalanceAndHolder();
        CountDownLatch httpEntered = new CountDownLatch(1);
        CountDownLatch releaseHttp = new CountDownLatch(1);
        when(authClient.findByEmail(anyString())).thenAnswer(inv -> {
            httpEntered.countDown();
            if (!releaseHttp.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("La llamada HTTP no se liberó");
            }
            return new AuthClient.AuthUserResponse(9L, "nuevo@bytebank.test", Role.CLIENTE);
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> add = pool.submit(() -> accountService.addHolder(
                new AuthUser(1L, "titular@bytebank.test", Role.CLIENTE), account.getIban(), "nuevo@bytebank.test"));
        try {
            assertThat(httpEntered.await(5, TimeUnit.SECONDS)).isTrue();

            var result = ledgerService.debit(new MovementRequest("TRF-LOCK", account.getIban(),
                    new BigDecimal("1.00"), MovementType.TRANSFERENCIA_EMITIDA, "test"));
            assertThat(result.balanceAfter()).isEqualByComparingTo("9.00");
        } finally {
            releaseHttp.countDown();
            add.get(10, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    /** El lock sobre la cuenta sigue garantizando el máximo de 4 titulares con altas simultáneas. */
    @Test
    void concurrentAddsNeverExceedFourHolders() throws Exception {
        Account account = accountWithBalanceAndHolder();
        holders.save(new AccountHolder(account.getId(), 2L));
        holders.save(new AccountHolder(account.getId(), 3L));

        when(authClient.findByEmail("a@bytebank.test"))
                .thenReturn(new AuthClient.AuthUserResponse(10L, "a@bytebank.test", Role.CLIENTE));
        when(authClient.findByEmail("b@bytebank.test"))
                .thenReturn(new AuthClient.AuthUserResponse(11L, "b@bytebank.test", Role.CLIENTE));

        AuthUser requester = new AuthUser(1L, "titular@bytebank.test", Role.CLIENTE);
        Callable<Account> addA = () -> accountService.addHolder(requester, account.getIban(), "a@bytebank.test");
        Callable<Account> addB = () -> accountService.addHolder(requester, account.getIban(), "b@bytebank.test");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Account> first = pool.submit(() -> { start.await(); return addA.call(); });
        Future<Account> second = pool.submit(() -> { start.await(); return addB.call(); });
        start.countDown();

        int rejected = 0;
        for (Future<Account> future : List.of(first, second)) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(ApiException.class);
                assertThat(((ApiException) e.getCause()).getCode()).isEqualTo("MAXIMO_TITULARES");
                rejected++;
            }
        }
        pool.shutdownNow();

        assertThat(rejected).isEqualTo(1);
        assertThat(holders.countByAccountId(account.getId())).isEqualTo(4);
    }
}
