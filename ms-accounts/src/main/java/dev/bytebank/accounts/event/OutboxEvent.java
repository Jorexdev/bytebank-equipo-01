package dev.bytebank.accounts.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Evento pendiente de enviar a Kafka. Se guarda en la misma transacción que el movimiento. */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String message_key;

    @Column(nullable = false)
    private String payload;

    protected OutboxEvent() {
    }

    public OutboxEvent(String message_key, String payload) {
        this.message_key = message_key;
        this.payload = payload;
    }

    public String getKey() {
        return message_key;
    }

    public String getPayload() {
        return payload;
    }
}
