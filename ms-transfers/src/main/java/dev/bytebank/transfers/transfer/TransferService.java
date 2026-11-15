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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final TransferRepository transfers;
    private final AccountsClient accountsClient;
    private final FraudClient fraudClient;

    public TransferService(TransferRepository transfers, AccountsClient accountsClient, FraudClient fraudClient) {
        this.transfers = transfers;
        this.accountsClient = accountsClient;
        this.fraudClient = fraudClient;
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
        if (checkResponse.verdict() != Verdict.APROBADA) {
            throw ApiException.badRequest("TRANSACCION_FRAUDULENTA", checkResponse.reasons().toString());
        }

        Transfer transfer = transfers.save(new Transfer(origin, destination, amount, request.concept(), user.id()));
        try {
            accountsClient.debit(transfer.ledgerReference(), origin, amount, request.concept());
        } catch (ApiException e) {
            transfer.reject(e.getCode());
            log.info("Transferencia {} rechazada: {}", transfer.getId(), e.getCode());
            return transfers.save(transfer);
        }

        if (Iban.isOwnBank(destination)) {
            try {
                accountsClient.credit(transfer.ledgerReference(), destination, amount, request.concept());
            } catch (ApiException e) {
                log.warn("No se pudo abonar la transferencia {} en {}: {}", transfer.getId(), destination, e.getCode());
                transfer.reject(e.getCode());
                return transfers.save(transfer);
            }
        }
        transfer.complete();
        log.info("Transferencia {} completada", transfer.getId());
        return transfers.save(transfer);
    }

    public Transfer get(Long id, AuthUser user) {
        Transfer transfer = transfers.findById(id)
                .orElseThrow(() -> ApiException.notFound("TRANSFERENCIA_NO_ENCONTRADA", "No existe la transferencia " + id));
        if (!transfer.getCreatedBy().equals(user.id())) {
            throw ApiException.forbidden("No puedes ver esta transferencia");
        }
        return transfer;
    }

    public PageResponse<TransferResponse> list(int page, int size, AuthUser user) {
        var result = transfers.findByCreatedBy(user.id(), PageRequest.of(page, size, NEWEST_FIRST));
        return PageResponse.of(result, TransferResponse::from);
    }
}
