package dev.bytebank.accounts.movement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface MovementRepository extends JpaRepository<Movement, Long> {

    Page<Movement> findByAccountIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Long accountId, Instant from, Instant to, Pageable pageable);
}
