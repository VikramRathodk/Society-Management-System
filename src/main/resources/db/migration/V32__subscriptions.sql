-- Subscription & billing (module 30), step S1: the platform's plans, each society's subscription with its
-- trial, and the subscription's history. Invoices, payments and the billing run come in later steps.

-- Prices in rupees: per flat per month (monthly cycle) and per flat per year (yearly cycle), with a
-- minimum monthly charge. `modules` lists the paid modules the plan includes (core modules are in every
-- plan): MAINTENANCE, FINANCE, POLICIES, GOVERNANCE.
CREATE TABLE plan (
    id                     BIGSERIAL     PRIMARY KEY,
    code                   VARCHAR(30)   NOT NULL UNIQUE,
    name                   VARCHAR(100)  NOT NULL,
    description            VARCHAR(1000),
    price_per_flat_monthly NUMERIC(10,2) NOT NULL,
    price_per_flat_yearly  NUMERIC(10,2) NOT NULL,
    minimum_monthly        NUMERIC(10,2) NOT NULL DEFAULT 0,
    max_flats              INTEGER,
    trial_days             INTEGER       NOT NULL DEFAULT 30,
    modules                VARCHAR(500)  NOT NULL DEFAULT '',
    active                 BOOLEAN       NOT NULL DEFAULT true,
    sort_order             INTEGER       NOT NULL DEFAULT 0,
    created_at             TIMESTAMP     NOT NULL,
    updated_at             TIMESTAMP,
    CONSTRAINT plan_prices_check CHECK (price_per_flat_monthly >= 0 AND price_per_flat_yearly >= 0 AND minimum_monthly >= 0),
    CONSTRAINT plan_trial_check CHECK (trial_days BETWEEN 0 AND 365),
    CONSTRAINT plan_max_flats_check CHECK (max_flats IS NULL OR max_flats > 0)
);

-- One per society. A plan or cycle change waits in `pending_*` until the next period starts (during a
-- trial it applies at once).
CREATE TABLE subscription (
    id                    BIGSERIAL     PRIMARY KEY,
    society_id            BIGINT        NOT NULL UNIQUE REFERENCES society(id),
    plan_id               BIGINT        NOT NULL REFERENCES plan(id),
    cycle                 VARCHAR(10)   NOT NULL,
    status                VARCHAR(10)   NOT NULL,
    trial_ends_on         DATE,
    current_period_start  DATE          NOT NULL,
    current_period_end    DATE          NOT NULL,
    next_invoice_on       DATE          NOT NULL,
    pending_plan_id       BIGINT        REFERENCES plan(id),
    pending_cycle         VARCHAR(10),
    billing_contact_id    BIGINT        REFERENCES users(id),
    discount_percent      INTEGER       NOT NULL DEFAULT 0,
    discount_periods_left INTEGER       NOT NULL DEFAULT 0,
    cancelled_at          TIMESTAMP,
    created_at            TIMESTAMP     NOT NULL,
    created_by            BIGINT        REFERENCES users(id),
    updated_at            TIMESTAMP,
    CONSTRAINT subscription_cycle_check CHECK (cycle IN ('MONTHLY', 'YEARLY')),
    CONSTRAINT subscription_pending_cycle_check CHECK (pending_cycle IS NULL OR pending_cycle IN ('MONTHLY', 'YEARLY')),
    CONSTRAINT subscription_status_check CHECK (status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED')),
    CONSTRAINT subscription_discount_check CHECK (discount_percent BETWEEN 0 AND 100 AND discount_periods_left >= 0),
    CONSTRAINT subscription_period_check CHECK (current_period_end >= current_period_start)
);

-- Everything that happened to a subscription, newest last.
CREATE TABLE subscription_event (
    id              BIGSERIAL    PRIMARY KEY,
    subscription_id BIGINT       NOT NULL REFERENCES subscription(id) ON DELETE CASCADE,
    kind            VARCHAR(30)  NOT NULL,
    from_value      VARCHAR(100),
    to_value        VARCHAR(100),
    note            VARCHAR(500),
    created_by      BIGINT       REFERENCES users(id),
    created_at      TIMESTAMP    NOT NULL
);
CREATE INDEX subscription_event_idx ON subscription_event (subscription_id, created_at);

INSERT INTO plan (code, name, description, price_per_flat_monthly, price_per_flat_yearly, minimum_monthly, trial_days, modules, sort_order, created_at) VALUES
('BASIC', 'Basic', 'Visitors, notices, complaints and maintenance billing.', 10.00, 100.00, 500.00, 30, 'MAINTENANCE', 1, now()),
('STANDARD', 'Standard', 'Everything in Basic, plus finance and accounts, rules and policies, and meetings with e-voting.', 20.00, 200.00, 1000.00, 30, 'MAINTENANCE,FINANCE,POLICIES,GOVERNANCE', 2, now()),
('PREMIUM', 'Premium', 'Every module, including the ones added later, and priority support.', 30.00, 300.00, 1500.00, 30, 'MAINTENANCE,FINANCE,POLICIES,GOVERNANCE,ALL', 3, now());

-- Platform billing is for SUPER_ADMIN (all permissions); the society's own subscription for SECRETARY and above.
INSERT INTO permission (module, feature, subfeature, action, code, description) VALUES
('PLATFORM','BILLING',NULL,'VIEW','PLATFORM.BILLING.VIEW','See plans, every society''s subscription, invoices and payments'),
('PLATFORM','BILLING',NULL,'UPDATE','PLATFORM.BILLING.UPDATE','Manage plans and subscriptions, invoices, payments and discounts'),
('SOCIETY','SUBSCRIPTION',NULL,'VIEW','SOCIETY.SUBSCRIPTION.VIEW','See the society''s plan, invoices and receipts'),
('SOCIETY','SUBSCRIPTION',NULL,'UPDATE','SOCIETY.SUBSCRIPTION.UPDATE','Change the billing contact and ask for a plan change');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('SOCIETY.SUBSCRIPTION.VIEW', 'SOCIETY.SUBSCRIPTION.UPDATE')
WHERE r.code = 'SECRETARY';
