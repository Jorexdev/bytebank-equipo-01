CREATE TABLE outbox (
    id             BIGSERIAL   PRIMARY KEY,
    message_key    VARCHAR(64) NOT NULL,
    payload        TEXT     NOT NULL
);
