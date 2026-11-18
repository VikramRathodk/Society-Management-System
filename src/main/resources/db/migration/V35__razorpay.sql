-- Subscription & billing, step S3: paying platform invoices online through Razorpay.

-- An order created at Razorpay for an invoice's amount due; Checkout pays it.
CREATE TABLE razorpay_order (
    id          BIGSERIAL    PRIMARY KEY,
    invoice_id  BIGINT       NOT NULL REFERENCES platform_invoice(id),
    order_id    VARCHAR(60)  NOT NULL UNIQUE,
    -- In paise, as Razorpay counts.
    amount      BIGINT       NOT NULL,
    status      VARCHAR(10)  NOT NULL,
    payment_id  VARCHAR(60),
    error       VARCHAR(500),
    created_by  BIGINT       REFERENCES users(id),
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP,
    CONSTRAINT razorpay_order_amount_check CHECK (amount > 0),
    CONSTRAINT razorpay_order_status_check CHECK (status IN ('CREATED', 'PAID', 'FAILED'))
);
CREATE INDEX razorpay_order_invoice_idx ON razorpay_order (invoice_id);

-- Every webhook delivery with a valid signature, once per event id (Razorpay retries): what it was and what
-- was done with it.
CREATE TABLE razorpay_webhook_event (
    id           BIGSERIAL    PRIMARY KEY,
    event_id     VARCHAR(100) NOT NULL UNIQUE,
    type         VARCHAR(60)  NOT NULL,
    payload      TEXT         NOT NULL,
    outcome      VARCHAR(500),
    received_at  TIMESTAMP    NOT NULL,
    processed_at TIMESTAMP
);
