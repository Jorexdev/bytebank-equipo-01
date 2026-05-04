package dev.bytebank.fraud.check;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudCheckRepository extends JpaRepository<FraudCheck, Long> {

    Page<FraudCheck> findByOriginIban(String originIban, Pageable pageable);
}
