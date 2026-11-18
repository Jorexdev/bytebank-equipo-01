package dev.bytebank.fraud.check;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface FraudCheckRepository extends JpaRepository<FraudCheck, Long> {

    Page<FraudCheck> findByOriginIban(String originIban, Pageable pageable);
    @Query("select count(c) from FraudCheck c "
            + "where c.originIban = :iban and c.verdict = :verdict and c.createdAt >= :since")
    long countSince(@Param("iban") String iban, @Param("verdict") Verdict verdict, @Param("since") Instant since);
    @Query(value = "select cast(pg_advisory_xact_lock(hashtext(:iban)) as text)", nativeQuery = true)
    String lockByIban(@Param("iban") String iban);
}
