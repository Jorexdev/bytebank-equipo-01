package dev.bytebank.accounts.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /** true si esta réplica es la única que vacía la outbox ahora. El bloqueo se libera al acabar la transacción. */
    @Query(value = "select pg_try_advisory_xact_lock(1)", nativeQuery = true)
    boolean tryLock();

    List<OutboxEvent> findTop100ByOrderByIdAsc();
}
