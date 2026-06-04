package dev.bytebank.accounts.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytebank.accounts.account.Account;
import dev.bytebank.accounts.movement.Movement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publica cada movimiento en Kafka para que ms-notifications avise al cliente. */
@Component
public class MovementPublisher {

    private static final Logger log = LoggerFactory.getLogger(MovementPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public MovementPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    public void publish(Movement movement, Account account) {
        try {
            String payload = objectMapper.writeValueAsString(MovementEvent.of(movement, account));
            kafka.send(MovementEvent.TOPIC, account.getIban(), payload);
            log.debug("Movimiento {} publicado", movement.getId());
        } catch (JsonProcessingException e) {
            log.error("No se ha podido publicar el movimiento {}", movement.getId(), e);
        }
    }
}
