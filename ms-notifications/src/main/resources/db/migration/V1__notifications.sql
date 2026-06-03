CREATE TABLE notifications (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT         NOT NULL,
    movement_id   BIGINT         NOT NULL,
    iban          VARCHAR(34)    NOT NULL,
    type          VARCHAR(30)    NOT NULL,
    amount        NUMERIC(19, 2) NOT NULL,
    balance_after NUMERIC(19, 2) NOT NULL,
    reference     VARCHAR(64),
    message       VARCHAR(300)   NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL
);

CREATE INDEX idx_notifications_user ON notifications (user_id);
