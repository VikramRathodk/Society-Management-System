-- Subscription & billing, step S2: the platform's invoices to societies (one per billing period, with GST),
-- payments against them with receipts, and credit notes that correct an invoice.

-- Everything printed on the invoice is frozen when it is issued: plan, prices, flat count, the society's
-- name and address, the platform's GSTIN, and the tax split.
CREATE TABLE platform_invoice (
    id                 BIGSERIAL     PRIMARY KEY,
    number             VARCHAR(40)   NOT NULL UNIQUE,
    society_id         BIGINT        NOT NULL REFERENCES society(id),
    subscription_id    BIGINT        NOT NULL REFERENCES subscription(id),
    period_start       DATE          NOT NULL,
    period_end         DATE          NOT NULL,
    plan_code          VARCHAR(30)   NOT NULL,
    plan_name          VARCHAR(100)  NOT NULL,
    cycle              VARCHAR(10)   NOT NULL,
    flats              INTEGER       NOT NULL,
    rate_per_flat      NUMERIC(10,2) NOT NULL,
    minimum_charge     NUMERIC(12,2) NOT NULL,
    amount             NUMERIC(12,2) NOT NULL,
    discount_percent   INTEGER       NOT NULL DEFAULT 0,
    discount_amount    NUMERIC(12,2) NOT NULL DEFAULT 0,
    taxable_value      NUMERIC(12,2) NOT NULL,
    cgst               NUMERIC(12,2) NOT NULL DEFAULT 0,
    sgst               NUMERIC(12,2) NOT NULL DEFAULT 0,
    igst               NUMERIC(12,2) NOT NULL DEFAULT 0,
    total              NUMERIC(12,2) NOT NULL,
    paid_amount        NUMERIC(12,2) NOT NULL DEFAULT 0,
    credited_amount    NUMERIC(12,2) NOT NULL DEFAULT 0,
    status             VARCHAR(12)   NOT NULL,
    issued_on          DATE          NOT NULL,
    due_on             DATE          NOT NULL,
    society_name       VARCHAR(255)  NOT NULL,
    society_address    VARCHAR(1000),
    society_state      VARCHAR(100),
    platform_gstin     VARCHAR(20),
    created_by         BIGINT        REFERENCES users(id),
    created_at         TIMESTAMP     NOT NULL,
    CONSTRAINT platform_invoice_status_check CHECK (status IN ('ISSUED', 'PARTLY_PAID', 'PAID')),
    CONSTRAINT platform_invoice_amounts_check CHECK (total >= 0 AND paid_amount >= 0 AND credited_amount >= 0 AND paid_amount + credited_amount <= total),
    CONSTRAINT platform_invoice_period_check CHECK (period_end >= period_start)
);
CREATE INDEX platform_invoice_society_idx ON platform_invoice (society_id, issued_on DESC);
-- One invoice per subscription period.
CREATE UNIQUE INDEX platform_invoice_period_idx ON platform_invoice (subscription_id, period_start);

CREATE TABLE platform_payment (
    id                 BIGSERIAL     PRIMARY KEY,
    receipt_number     VARCHAR(40)   NOT NULL UNIQUE,
    invoice_id         BIGINT        NOT NULL REFERENCES platform_invoice(id),
    amount             NUMERIC(12,2) NOT NULL,
    mode               VARCHAR(15)   NOT NULL,
    reference          VARCHAR(100),
    paid_on            DATE          NOT NULL,
    -- Razorpay's payment id (step S3), unique so a webhook twice records once.
    gateway_payment_id VARCHAR(60)   UNIQUE,
    recorded_by        BIGINT        REFERENCES users(id),
    created_at         TIMESTAMP     NOT NULL,
    CONSTRAINT platform_payment_amount_check CHECK (amount > 0),
    CONSTRAINT platform_payment_mode_check CHECK (mode IN ('RAZORPAY', 'BANK_TRANSFER', 'UPI', 'CHEQUE', 'CASH'))
);
CREATE INDEX platform_payment_invoice_idx ON platform_payment (invoice_id);

CREATE TABLE platform_credit_note (
    id         BIGSERIAL     PRIMARY KEY,
    number     VARCHAR(40)   NOT NULL UNIQUE,
    invoice_id BIGINT        NOT NULL REFERENCES platform_invoice(id),
    amount     NUMERIC(12,2) NOT NULL,
    reason     VARCHAR(500)  NOT NULL,
    created_by BIGINT        REFERENCES users(id),
    created_at TIMESTAMP     NOT NULL,
    CONSTRAINT platform_credit_note_amount_check CHECK (amount > 0)
);

-- Numbers per kind (INV, RCPT, CN) and financial year (April–March): SAH/2026-27/0042.
CREATE TABLE platform_counter (
    kind           VARCHAR(10) NOT NULL,
    financial_year VARCHAR(10) NOT NULL,
    last_number    INTEGER     NOT NULL,
    PRIMARY KEY (kind, financial_year)
);
