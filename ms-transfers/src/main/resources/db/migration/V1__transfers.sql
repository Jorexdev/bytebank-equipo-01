CREATE TABLE transfers (
    id               BIGSERIAL PRIMARY KEY,
    origin_iban      VARCHAR(34)    NOT NULL,
    destination_iban VARCHAR(34)    NOT NULL,
    amount           NUMERIC(19, 2) NOT NULL,
    concept          VARCHAR(140),
    status           VARCHAR(30)    NOT NULL,
    rejection_reason VARCHAR(40),
    created_by       BIGINT         NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_transfers_created_by ON transfers (created_by, created_at DESC);
