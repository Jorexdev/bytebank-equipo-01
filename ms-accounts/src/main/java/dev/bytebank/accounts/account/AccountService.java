package dev.bytebank.accounts.account;

import dev.bytebank.accounts.client.AuthClient;
import dev.bytebank.accounts.event.MovementPublisher;
import dev.bytebank.accounts.holder.AccountHolder;
import dev.bytebank.accounts.holder.AccountHolderRepository;
import dev.bytebank.accounts.movement.Movement;
import dev.bytebank.accounts.movement.MovementRepository;
import dev.bytebank.accounts.movement.MovementType;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private static final int MAX_HOLDERS = 4;

    private final AccountRepository accounts;
    private final MovementRepository movements;
    private final MovementPublisher publisher;
    private final AccountHolderRepository holders;
    private final AuthClient authClient;

    public AccountService(AccountRepository accounts, MovementRepository movements, MovementPublisher publisher,
                          AccountHolderRepository holders, AuthClient authClient) {
        this.accounts = accounts;
        this.movements = movements;
        this.publisher = publisher;
        this.holders = holders;
        this.authClient = authClient;
    }

    @Transactional
    public Account open(AuthUser user) {
        Account account = accounts.save(new Account(Iban.generate(accounts.nextAccountNumber()), user.id()));
        holders.save(new AccountHolder(account.getId(), user.id()));
        log.info("Cuenta {} abierta por el usuario {}", account.getIban(), user.id());
        return account;
    }

    @Transactional(readOnly = true)
    public List<Account> listOwn(AuthUser user) {
        return accounts.findByHolderIdOrderByCreatedAt(user.id());
    }

    @Transactional(readOnly = true)
    public Account get(AuthUser user, String iban) {
        if (user.isGestor()) {
            return getAccountByIban(iban);
        }
        return accounts.findByIbanAndHolderId(Iban.normalize(iban), user.id()).orElseThrow(
                () -> ApiException.notFound("CUENTA_NO_ENCONTRADA", "La cuenta no existe entre sus cuentas asociadas")
        );
    }

    /** Añade un titular a la cuenta resolviendo su email en ms-auth. Máximo 4 titulares. */
    @Transactional
    public Account addHolder(AuthUser requester, String iban, String email) {
        Account account;
        try {
            account = accounts.findByIbanForUpdate(Iban.normalize(iban)).orElseThrow(
                    () -> ApiException.notFound("CUENTA_NO_ENCONTRADA", "La cuenta no existe"));
        } catch (PessimisticLockingFailureException e) {
            throw ApiException.conflict("CUENTA_OCUPADA", "La cuenta está ocupada, inténtalo de nuevo");
        }
        if (!holders.existsByAccountIdAndUserId(account.getId(), requester.id())) {
            throw ApiException.notFound("CUENTA_NO_ENCONTRADA", "La cuenta no existe entre sus cuentas asociadas");
        }
        if (email == null || email.isBlank()) {
            throw ApiException.badRequest("EMAIL_NO_VALIDO", "El email del nuevo titular es obligatorio");
        }
        AuthClient.AuthUserResponse candidate = authClient.findByEmail(email.trim().toLowerCase(Locale.ROOT));
        if (candidate.role() != Role.CLIENTE) {
            throw ApiException.badRequest("TITULAR_NO_VALIDO", "Solo se puede añadir como titular a un cliente");
        }
        if (holders.existsByAccountIdAndUserId(account.getId(), candidate.id())) {
            throw ApiException.conflict("YA_ES_TITULAR", "Ese cliente ya es titular de la cuenta");
        }
        if (holders.countByAccountId(account.getId()) >= MAX_HOLDERS) {
            throw ApiException.conflict("MAXIMO_TITULARES", "La cuenta ya tiene el máximo de 4 titulares");
        }
        holders.save(new AccountHolder(account.getId(), candidate.id()));
        log.info("Titular {} añadido a la cuenta {}", candidate.id(), account.getIban());
        return account;
    }

    @Transactional(readOnly = true)
    public List<Long> holderIds(Account account) {
        return holders.findUserIdsByAccountId(account.getId());
    }

    @Transactional(readOnly = true)
    public Account getAccountByIban(String iban) {
        return accounts.findByIban(Iban.normalize(iban)).orElseThrow(
                () -> ApiException.notFound("CUENTA_NO_ENCONTRADA", "Cuenta no encontrada")
        );
    }

    @Transactional
    public Account changeStatus(String iban, AccountStatus status) {
        Account account = getAccountByIban(iban);
        account.changeStatus(status);
        log.info("Cuenta {} pasa a {}", account.getIban(), status);
        return account;
    }

    @Transactional
    public Account deposit(String iban, BigDecimal amount, String concept) {
        validateAmount(amount);
        Account account = getAccountByIban(iban);
        if (!account.isActive()) {
            throw ApiException.conflict("CUENTA_NO_OPERATIVA", "La cuenta no está activa");
        }
        account.credit(amount);
        Movement movement = movements.save(new Movement(account, MovementType.INGRESO, amount,
                concept == null || concept.isBlank() ? "Ingreso" : concept, null));
        publisher.publish(movement, account);
        return account;
    }
    
    private static void validateAmount(BigDecimal amount) {
        if (amount == null) {
            throw ApiException.badRequest("IMPORTE_NO_VALIDO", "El importe es obligatorio");
        }
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("IMPORTE_NO_VALIDO", "El importe debe ser mayor que cero");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.badRequest("IMPORTE_NO_VALIDO", "El importe admite como máximo 2 decimales");
        }
    }
    
}
