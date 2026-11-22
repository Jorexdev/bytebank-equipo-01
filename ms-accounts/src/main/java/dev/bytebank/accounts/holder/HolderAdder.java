package dev.bytebank.accounts.holder;

import dev.bytebank.accounts.account.Account;
import dev.bytebank.accounts.account.AccountRepository;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Alta de titulares: transacción corta, con el bloqueo solo después de validar al solicitante. */
@Service
public class HolderAdder {

    private static final int MAX_HOLDERS = 4;

    private final AccountRepository accounts;
    private final AccountHolderRepository holders;

    public HolderAdder(AccountRepository accounts, AccountHolderRepository holders) {
        this.accounts = accounts;
        this.holders = holders;
    }

    @Transactional
    public Account add(Long requesterId, String iban, Long candidateId) {
        String normalized = Iban.normalize(iban);

        // Lectura sin bloqueo: un ajeno no debe llegar a bloquear la fila de la cuenta.
        Account found = accounts.findByIban(normalized).orElseThrow(
                () -> ApiException.notFound("CUENTA_NO_ENCONTRADA", "La cuenta no existe entre sus cuentas asociadas"));
        if (!holders.existsByAccountIdAndUserId(found.getId(), requesterId)) {
            throw ApiException.notFound("CUENTA_NO_ENCONTRADA", "La cuenta no existe entre sus cuentas asociadas");
        }

        // Desde aquí solo trabajo local: el lock se retiene unos pocos milisegundos.
        Account account;
        try {
            account = accounts.findByIbanForUpdate(normalized).orElseThrow(
                    () -> ApiException.notFound("CUENTA_NO_ENCONTRADA", "La cuenta no existe"));
        } catch (PessimisticLockingFailureException e) {
            throw ApiException.conflict("CUENTA_OCUPADA", "La cuenta está ocupada, inténtalo de nuevo");
        }

        // Revalidar bajo lock: cierra la ventana entre la lectura anterior y el bloqueo.
        if (!holders.existsByAccountIdAndUserId(account.getId(), requesterId)) {
            throw ApiException.notFound("CUENTA_NO_ENCONTRADA", "La cuenta no existe entre sus cuentas asociadas");
        }
        if (holders.existsByAccountIdAndUserId(account.getId(), candidateId)) {
            throw ApiException.conflict("YA_ES_TITULAR", "Ese cliente ya es titular de la cuenta");
        }
        if (holders.countByAccountId(account.getId()) >= MAX_HOLDERS) {
            throw ApiException.conflict("MAXIMO_TITULARES", "La cuenta ya tiene el máximo de 4 titulares");
        }

        holders.save(new AccountHolder(account.getId(), candidateId));
        return account;
    }
}
