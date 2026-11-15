package dev.bytebank.accounts.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByIban(String iban);

    Optional<Account> findByIbanAndOwnerId(String iban, Long ownerId);

    List<Account> findByOwnerIdOrderByCreatedAt(Long ownerId);

    @Query(value = "select nextval('account_number_seq')", nativeQuery = true)
    long nextAccountNumber();
}
