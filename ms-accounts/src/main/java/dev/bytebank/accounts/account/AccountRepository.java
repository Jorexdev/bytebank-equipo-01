package dev.bytebank.accounts.account;

import dev.bytebank.accounts.holder.AccountHolder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByIban(String iban);

    Optional<Account> findByIbanAndOwnerId(String iban, Long ownerId);

    List<Account> findByOwnerIdOrderByCreatedAt(Long ownerId);

    @Query("select a from Account a join AccountHolder h on h.accountId = a.id " +
            "where a.iban = :iban and h.userId = :userId")
    Optional<Account> findByIbanAndHolderId(@Param("iban") String iban, @Param("userId") Long userId);

    @Query("select a from Account a join AccountHolder h on h.accountId = a.id " +
            "where h.userId = :userId order by a.createdAt")
    List<Account> findByHolderIdOrderByCreatedAt(@Param("userId") Long userId);

    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountNumber();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.iban = :iban")
    Optional<Account> findByIbanForUpdate(@Param("iban") String iban);
}
