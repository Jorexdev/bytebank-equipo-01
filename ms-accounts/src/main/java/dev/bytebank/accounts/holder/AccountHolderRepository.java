package dev.bytebank.accounts.holder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AccountHolderRepository extends JpaRepository<AccountHolder, Long> {

    boolean existsByAccountIdAndUserId(Long accountId, Long userId);

    long countByAccountId(Long accountId);

    @Query("select h.userId from AccountHolder h where h.accountId = :accountId order by h.userId")
    List<Long> findUserIdsByAccountId(@Param("accountId") Long accountId);
}
