package dev.bytebank.accounts.account;

import dev.bytebank.accounts.client.AuthClient;
import dev.bytebank.accounts.event.MovementPublisher;
import dev.bytebank.accounts.holder.AccountHolder;
import dev.bytebank.accounts.holder.AccountHolderRepository;
import dev.bytebank.accounts.holder.HolderAdder;
import dev.bytebank.accounts.movement.Movement;
import dev.bytebank.accounts.movement.MovementRepository;
import dev.bytebank.accounts.movement.MovementType;
import dev.bytebank.common.error.ApiException;
import dev.bytebank.common.iban.Iban;
import dev.bytebank.common.security.AuthUser;
import dev.bytebank.common.security.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accounts;
    private final MovementRepository movements;
    private final MovementPublisher publisher;
    private final AccountHolderRepository holders;
    private final AuthClient authClient;
    private final HolderAdder holderAdder;

    public AccountService(AccountRepository accounts, MovementRepository movements, MovementPublisher publisher,
                          AccountHolderRepository holders, AuthClient authClient, HolderAdder holderAdder) {
        this.accounts = accounts;
        this.movements = movements;
        this.publisher = publisher;
        this.holders = holders;
        this.authClient = authClient;
        this.holderAdder = holderAdder;
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

    /**
     * Añade un titular resolviendo su email en ms-auth primero para evitar que el bloqueo
     * al hacer la consulta ForUpdate quede atascado esperando la respuesta de ms-auth
     */
    public Account addHolder(AuthUser holder, String iban, String email) {
        if (email == null || email.isBlank()) {
            throw ApiException.badRequest("EMAIL_NO_VALIDO", "El email del nuevo titular es obligatorio");
        }
        AuthClient.AuthUserResponse candidate = authClient.findByEmail(email.trim().toLowerCase(Locale.ROOT));
        if (candidate.role() != Role.CLIENTE) {
            throw ApiException.badRequest("TITULAR_NO_VALIDO", "Solo se puede añadir como titular a un cliente");
        }
        return holderAdder.add(holder.id(), iban, candidate.id());
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
