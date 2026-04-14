CREATE SEQUENCE account_number_seq START 1;

CREATE TABLE accounts (
    id         BIGSERIAL PRIMARY KEY,
    iban       VARCHAR(34)      NOT NULL UNIQUE,
    status     VARCHAR(20)      NOT NULL,
    owner_id   BIGINT           NOT NULL,
    balance    DOUBLE PRECISION NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ      NOT NULL
);

CREATE TABLE movements (
    id            BIGSERIAL PRIMARY KEY,
    account_id    BIGINT           NOT NULL REFERENCES accounts (id),
    type          VARCHAR(30)      NOT NULL,
    amount        DOUBLE PRECISION NOT NULL,
    balance_after DOUBLE PRECISION NOT NULL,
    concept       VARCHAR(140)     NOT NULL,
    created_at    TIMESTAMPTZ      NOT NULL
);

CREATE INDEX idx_movements_account_date ON movements (account_id, created_at DESC);
