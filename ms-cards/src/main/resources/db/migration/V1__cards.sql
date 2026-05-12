CREATE TABLE cards (
    id           BIGSERIAL PRIMARY KEY,
    account_iban VARCHAR(34)    NOT NULL,
    user_id      BIGINT         NOT NULL,
    last4        VARCHAR(4)     NOT NULL,
    status       VARCHAR(20)    NOT NULL,
    daily_limit  NUMERIC(19, 2) NOT NULL,
    created_at   TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_cards_user ON cards (user_id);

CREATE TABLE card_payments (
    id               BIGSERIAL PRIMARY KEY,
    card_id          BIGINT         NOT NULL REFERENCES cards (id),
    amount           NUMERIC(19, 2) NOT NULL,
    merchant         VARCHAR(100)   NOT NULL,
    status           VARCHAR(20)    NOT NULL,
    rejection_reason VARCHAR(40),
    created_at       TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_card_payments_card_date ON card_payments (card_id, created_at DESC);
