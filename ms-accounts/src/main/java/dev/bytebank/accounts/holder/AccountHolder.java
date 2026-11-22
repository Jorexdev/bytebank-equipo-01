package dev.bytebank.accounts.holder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** Titular de una cuenta. El creador también se guarda aquí como primer titular. */
@Entity
@Table(name = "account_holders",
        uniqueConstraints = @UniqueConstraint(name = "uq_account_holders", columnNames = {"account_id", "user_id"}))
public class AccountHolder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AccountHolder() {
    }

    public AccountHolder(Long accountId, Long userId) {
        this.accountId = accountId;
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
