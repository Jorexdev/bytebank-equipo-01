package dev.bytebank.transfers.transfer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Page<Transfer> findByCreatedBy(Long createdBy, Pageable pageable);
}
