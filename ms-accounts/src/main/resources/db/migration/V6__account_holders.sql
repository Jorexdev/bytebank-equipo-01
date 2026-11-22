-- Una cuenta puede tener varios titulares (máximo 4, validado en la aplicación).
CREATE TABLE account_holders (
    id         BIGSERIAL PRIMARY KEY,
    account_id BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_account_holders UNIQUE (account_id, user_id)
);

-- La lista de cuentas de un cliente y la comprobación de titularidad filtran por user_id.
CREATE INDEX idx_account_holders_user ON account_holders (user_id);

-- Las cuentas existentes conservan a su creador como titular.
INSERT INTO account_holders (account_id, user_id, created_at)
SELECT id, owner_id, created_at FROM accounts;
