-- Tirvex Tech subscriptions: what each school pays (monthly or yearly, no student limit), its invoices and payments.
-- Platform tables (no row-level security). An unpaid invoice past its due date plus the grace days pauses the
-- school's access, the same way an ended trial does; marking it paid restores access at once.

-- Owner settings, e.g. the payment details printed on invoices.
CREATE TABLE platform_setting (
    key         VARCHAR(60)  PRIMARY KEY,
    value       TEXT,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The column is not called school_id: removing a school removes its subscription by the foreign key, not row by row.
CREATE TABLE subscription (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscribed_school_id  BIGINT         NOT NULL UNIQUE REFERENCES school (id) ON DELETE CASCADE,
    cycle                 VARCHAR(10)    NOT NULL CHECK (cycle IN ('MONTHLY', 'YEARLY')),
    amount                NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    started_on            DATE           NOT NULL,
    next_due_on           DATE           NOT NULL,
    grace_days            INT            NOT NULL DEFAULT 10 CHECK (grace_days BETWEEN 0 AND 60),
    status                VARCHAR(10)    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED')),
    notes                 VARCHAR(500),
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Invoices are kept (with the school's code and name) even if the school is deleted later.
CREATE TABLE platform_invoice (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    invoice_no              VARCHAR(20)    NOT NULL UNIQUE,
    billed_school_id        BIGINT         REFERENCES school (id) ON DELETE SET NULL,
    school_code             VARCHAR(20)    NOT NULL,
    school_name             VARCHAR(200)   NOT NULL,
    subscription_id         BIGINT         REFERENCES subscription (id) ON DELETE SET NULL,
    cycle                   VARCHAR(10)    NOT NULL,
    period_start            DATE           NOT NULL,
    period_end              DATE           NOT NULL,
    amount                  NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    issued_on               DATE           NOT NULL,
    due_on                  DATE           NOT NULL,
    grace_days              INT            NOT NULL DEFAULT 10,
    status                  VARCHAR(10)    NOT NULL DEFAULT 'UNPAID' CHECK (status IN ('UNPAID', 'PAID', 'VOID')),
    paid_on                 DATE,
    payment_method          VARCHAR(30),
    payment_reference       VARCHAR(100),
    void_reason             VARCHAR(500),
    issued_notice_sent_at   TIMESTAMPTZ,
    due_notice_sent_at      TIMESTAMPTZ,
    paused_notice_sent_at   TIMESTAMPTZ,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT platform_invoice_period_uq UNIQUE (subscription_id, period_start)
);
CREATE INDEX platform_invoice_school_status_idx ON platform_invoice (billed_school_id, status);

GRANT SELECT, INSERT, UPDATE, DELETE ON platform_setting, subscription, platform_invoice TO sms_app;
