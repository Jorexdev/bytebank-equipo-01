CREATE TABLE fraud_checks (
    id          BIGSERIAL PRIMARY KEY,
    origin_iban VARCHAR(34)    NOT NULL,
    user_id     BIGINT         NOT NULL,
    amount      NUMERIC(19, 2) NOT NULL,
    verdict     VARCHAR(20)    NOT NULL,
    reasons     VARCHAR(255)   NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_fraud_checks_origin_date ON fraud_checks (origin_iban, created_at DESC);
