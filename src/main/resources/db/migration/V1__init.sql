CREATE TABLE payment (
    id               BIGSERIAL    PRIMARY KEY,
    order_id         VARCHAR(64)  NOT NULL,
    user_id          BIGINT       NOT NULL,
    amount           BIGINT       NOT NULL,
    canceled_amount  BIGINT       NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    payment_key      VARCHAR(64),
    failure_code     VARCHAR(50),
    failure_message  VARCHAR(200),
    requested_at     TIMESTAMPTZ  NOT NULL,
    approved_at      TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ  NOT NULL
);

CREATE TABLE payment_cancel (
    id           BIGSERIAL    PRIMARY KEY,
    payment_id   BIGINT       NOT NULL REFERENCES payment (id),
    amount       BIGINT       NOT NULL,
    reason       VARCHAR(200),
    canceled_at  TIMESTAMPTZ  NOT NULL
);
