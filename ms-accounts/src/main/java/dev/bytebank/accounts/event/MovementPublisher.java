package dev.bytebank.accounts.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytebank.accounts.account.Account;
import dev.bytebank.accounts.holder.AccountHolderRepository;
import dev.bytebank.accounts.movement.Movement;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Guarda cada movimiento en la outbox para que OutboxRelay lo publique en Kafka y ms-notifications avise al cliente. */
@Component
public class MovementPublisher {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final AccountHolderRepository holders;

    public MovementPublisher(OutboxRepository outbox, ObjectMapper objectMapper, AccountHolderRepository holders) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.holders = holders;
    }

    /** Va en la misma transacción que el movimiento: si hay rollback, no queda aviso. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Movement movement, Account account) {
        try {
            var holderIds = holders.findUserIdsByAccountId(account.getId());
            outbox.save(new OutboxEvent(account.getIban(),
                    objectMapper.writeValueAsString(MovementEvent.of(movement, account, holderIds))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el movimiento " + movement.getId(), e);
        }
    }
}
