package dev.bytebank.transfers.transfer;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Page<Transfer> findByCreatedBy(Long createdBy, Pageable pageable);

    Page<Transfer> findByStatus(TransferStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.id = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") Long id);

    /** Pasa de PENDIENTE_REVISION a EN_PROCESO en una sola sentencia; devuelve 0 si otro ya la resolvió. */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update Transfer t set t.status = dev.bytebank.transfers.transfer.TransferStatus.EN_PROCESO "
            + "where t.id = :id and t.status = dev.bytebank.transfers.transfer.TransferStatus.PENDIENTE_REVISION")
    int claimPending(@Param("id") Long id);
}
