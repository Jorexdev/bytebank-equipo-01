package dev.bytebank.accounts.event;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Vacía la tabla outbox hacia Kafka. Si el envío falla, hace rollback y reintenta el lote. */
@Component
public class OutboxRelay {

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    // shortcut: solo una réplica vacía la outbox a la vez (sin reparto de carga), repartir por IBAN si el volumen lo exige
    @Scheduled(fixedDelay = 500)
    @Transactional
    public void drain() {
        if (!outbox.tryLock()) return; // otra réplica está vaciando: así se respeta el orden por IBAN
        List<OutboxEvent> batch = outbox.findTop100ByOrderByIdAsc();
        batch.stream()
                .map(e -> kafka.send(MovementEvent.TOPIC, e.getKey(), e.getPayload()))
                .toList()
                .forEach(CompletableFuture::join);
        outbox.deleteAllInBatch(batch);
    }
}
