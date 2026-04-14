package dev.bytebank.accounts.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 34)
    private String iban;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private double balance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Account() {
    }

    public Account(String iban, Long ownerId) {
        this.iban = iban;
        this.ownerId = ownerId;
        this.status = AccountStatus.ACTIVA;
        this.balance = 0;
        this.createdAt = Instant.now();
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVA;
    }

    public void credit(double amount) {
        balance += amount;
    }

    public void debit(double amount) {
        balance -= amount;
    }

    public void changeStatus(AccountStatus status) {
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getIban() {
        return iban;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public double getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
