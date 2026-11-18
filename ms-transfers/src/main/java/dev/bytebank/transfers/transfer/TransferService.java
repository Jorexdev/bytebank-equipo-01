package dev.bytebank.transfers.transfer;

import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.web.PageResponse;
import dev.bytebank.transfers.client.AccountsClient;
import dev.bytebank.transfers.client.FraudClient;
import dev.bytebank.transfers.client.Verdict;
import dev.bytebank.transfers.transfer.TransferDtos.CreateTransferRequest;
import dev.bytebank.transfers.transfer.TransferDtos.TransferResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final String DEFAULT_REJECTION_REASON = "RECHAZADA_POR_GESTOR";

    private final TransferRepository transfers;
    private final AccountsClient accountsClient;
    private final FraudClient fraudClient;
    private final BigDecimal reviewThreshold;

    public TransferService(TransferRepository transfers, AccountsClient accountsClient, FraudClient fraudClient,
                           @Value("${bytebank.transfers.review-threshold:5000.00}") BigDecimal reviewThreshold) {
        this.transfers = transfers;
        this.accountsClient = accountsClient;
        this.fraudClient = fraudClient;
        this.reviewThreshold = reviewThreshold;
    }

    public Transfer create(CreateTransferRequest request, AuthUser user) {
        String origin = Iban.normalize(request.originIban());
        String destination = Iban.normalize(request.destinationIban());
        BigDecimal amount = request.amount();

        if (!Iban.isValid(destination)) {
            throw ApiException.badRequest("IBAN_NO_VALIDO", "El IBAN de destino no es válido");
        }
        if (origin.equals(destination)) {
            throw ApiException.badRequest("MISMA_CUENTA", "La cuenta de origen y la de destino son la misma");
        }
        AccountsClient.AccountInfo originAccount = accountsClient.getAccount(origin);
        if (!originAccount.ownerId().equals(user.id())) {
            throw ApiException.notFound("CUENTA_NO_ENCONTRADA", "No se encontró la cuenta de origen");
        }

        FraudClient.CheckResponse checkResponse = fraudClient.check(origin, destination, user.id(), amount);
        if (checkResponse.verdict() != Verdict.APROBADA && checkResponse.verdict() != Verdict.REVISAR) {
            throw ApiException.badRequest("TRANSACCION_FRAUDULENTA", checkResponse.reasons().toString());
        }

        boolean needsReview = checkResponse.verdict() == Verdict.REVISAR || amount.compareTo(reviewThreshold) > 0;
        Transfer transfer = new Transfer(origin, destination, amount, request.concept(), user.id());
        if (needsReview) {
            transfer.markPending();
        }
        transfer = transfers.save(transfer);
        if (needsReview) {
            log.info("Transferencia {} pendiente de revisión por un gestor", transfer.getId());
            return transfer;
        }
        return execute(transfer);
    }

    public Transfer get(Long id, AuthUser user) {
        Transfer transfer = transfers.findById(id)
                .orElseThrow(() -> ApiException.notFound("TRANSFERENCIA_NO_ENCONTRADA", "No existe la transferencia " + id));
        if (!user.isGestor() && !transfer.getCreatedBy().equals(user.id())) {
            throw ApiException.forbidden("No puedes ver esta transferencia");
        }
        return transfer;
    }

    public PageResponse<TransferResponse> list(int page, int size, AuthUser user) {
        var result = transfers.findByCreatedBy(user.id(), PageRequest.of(page, size, NEWEST_FIRST));
        return PageResponse.of(result, TransferResponse::from);
    }

    public PageResponse<TransferResponse> listPending(int page, int size, AuthUser user) {
        requireGestor(user);
        var result = transfers.findByStatus(TransferStatus.PENDIENTE, PageRequest.of(page, size, NEWEST_FIRST));
        return PageResponse.of(result, TransferResponse::from);
    }

    @Transactional
    public Transfer approve(Long id, AuthUser user) {
        requireGestor(user);
        return execute(lockPending(id));
    }

    @Transactional
    public Transfer reject(Long id, String reason, AuthUser user) {
        requireGestor(user);
        Transfer transfer = lockPending(id);
        transfer.reject(reason == null || reason.isBlank() ? DEFAULT_REJECTION_REASON : reason);
        log.info("Transferencia {} rechazada por un gestor: {}", transfer.getId(), transfer.getRejectionReason());
        return transfers.save(transfer);
    }

    /**
     * Movimiento único en el ledger para el camino inmediato y el de aprobación.
     * Carga en origen y, si el destino es de ByteBank, abona allí; deja la transferencia completada o rechazada.
     */
    private Transfer execute(Transfer transfer) {
        try {
            accountsClient.debit(transfer.ledgerReference(), transfer.getOriginIban(), transfer.getAmount(), transfer.getConcept());
        } catch (ApiException e) {
            transfer.reject(e.getCode());
            log.info("Transferencia {} rechazada: {}", transfer.getId(), e.getCode());
            return transfers.save(transfer);
        }

        if (Iban.isOwnBank(transfer.getDestinationIban())) {
            try {
                accountsClient.credit(transfer.ledgerReference(), transfer.getDestinationIban(), transfer.getAmount(), transfer.getConcept());
            } catch (ApiException e) {
                log.warn("No se pudo abonar la transferencia {} en {}: {}", transfer.getId(), transfer.getDestinationIban(), e.getCode());
                transfer.reject(e.getCode());
                return transfers.save(transfer);
            }
        }
        transfer.complete();
        log.info("Transferencia {} completada", transfer.getId());
        return transfers.save(transfer);
    }

    private Transfer lockPending(Long id) {
        Transfer transfer = transfers.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("TRANSFERENCIA_NO_ENCONTRADA", "No existe la transferencia " + id));
        if (transfer.getStatus() != TransferStatus.PENDIENTE) {
            throw ApiException.conflict("TRANSFERENCIA_YA_RESUELTA", "La transferencia " + id + " ya está resuelta");
        }
        return transfer;
    }

    private static void requireGestor(AuthUser user) {
        if (!user.isGestor()) {
            throw ApiException.forbidden("Solo un gestor puede revisar transferencias");
        }
    }
}
