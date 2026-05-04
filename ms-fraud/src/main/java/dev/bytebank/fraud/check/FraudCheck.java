package dev.bytebank.fraud.check;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Cada validación queda registrada: sirve de historial para la regla de frecuencia y para auditoría. */
@Entity
@Table(name = "fraud_checks")
public class FraudCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "origin_iban", nullable = false, length = 34)
    private String originIban;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Verdict verdict;

    /** Códigos de las reglas que han saltado, separados por comas. */
    @Column(nullable = false)
    private String reasons;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected FraudCheck() {
    }

    public FraudCheck(String originIban, Long userId, BigDecimal amount, Verdict verdict, List<String> reasons,
                      Instant createdAt) {
        this.originIban = originIban;
        this.userId = userId;
        this.amount = amount;
        this.verdict = verdict;
        this.reasons = String.join(",", reasons);
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getOriginIban() {
        return originIban;
    }

    public Long getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public List<String> getReasons() {
        return reasons.isEmpty() ? List.of() : Arrays.asList(reasons.split(","));
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
