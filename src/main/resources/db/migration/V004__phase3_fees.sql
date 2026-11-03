-- =====================================================================
-- V004 Phase 3: fees (FR 10, 4.10), minimal ledger and journal (completed in Phase 4), message outbox.
-- Money is NUMERIC(14,2). Financial history (bills, lines, payments, allocations, adjustments, journal) is
-- insert-only for the app role: corrections are new rows (reversals/adjustments) with a reason.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Ledger accounts and journal (minimal; Phase 4 adds the rest of school accounts)
-- ---------------------------------------------------------------------
CREATE TABLE ledger_account (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id      BIGINT       NOT NULL REFERENCES school (id),
    code           VARCHAR(20)  NOT NULL,
    name           VARCHAR(100) NOT NULL,
    account_type   VARCHAR(10)  NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY', 'INCOME', 'EXPENSE', 'EQUITY')),
    subtype        VARCHAR(20)  NOT NULL CHECK (subtype IN ('CASH', 'BANK', 'FEE_INCOME', 'STUDENT_ADVANCE', 'OTHER')),
    display_order  INT          NOT NULL DEFAULT 0,
    status         VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ledger_account_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT ledger_account_code_uq UNIQUE (school_id, code)
);

CREATE TABLE journal_entry (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id          BIGINT       NOT NULL REFERENCES school (id),
    entry_date         DATE         NOT NULL,
    entry_type         VARCHAR(20)  NOT NULL CHECK (entry_type IN ('FEE_RECEIPT', 'FEE_CREDIT', 'INCOME', 'EXPENSE',
                                                                    'TRANSFER', 'VENDOR', 'SALARY', 'REVERSAL', 'OPENING')),
    source_type        VARCHAR(30)  NOT NULL,
    source_id          BIGINT,
    memo               VARCHAR(500),
    reversed_entry_id  BIGINT,
    created_by         VARCHAR(200),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT journal_entry_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT journal_entry_reversed_fk FOREIGN KEY (school_id, reversed_entry_id) REFERENCES journal_entry (school_id, id)
);
CREATE INDEX journal_entry_source_idx ON journal_entry (school_id, source_type, source_id);

CREATE TABLE journal_line (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id          BIGINT         NOT NULL REFERENCES school (id),
    entry_id           BIGINT         NOT NULL,
    ledger_account_id  BIGINT         NOT NULL,
    debit              NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (debit >= 0),
    credit             NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (credit >= 0),
    CONSTRAINT journal_line_one_side CHECK (debit = 0 OR credit = 0),
    CONSTRAINT journal_line_entry_fk FOREIGN KEY (school_id, entry_id) REFERENCES journal_entry (school_id, id),
    CONSTRAINT journal_line_account_fk FOREIGN KEY (school_id, ledger_account_id) REFERENCES ledger_account (school_id, id)
);
CREATE INDEX journal_line_entry_idx ON journal_line (entry_id);

-- Every journal entry must balance (Σ debit = Σ credit), checked at commit.
CREATE FUNCTION journal_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    d NUMERIC;
    c NUMERIC;
BEGIN
    SELECT coalesce(sum(debit), 0), coalesce(sum(credit), 0) INTO d, c FROM journal_line WHERE entry_id = NEW.entry_id;
    IF d <> c THEN
        RAISE EXCEPTION 'Journal entry % is not balanced (debit %, credit %)', NEW.entry_id, d, c;
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER journal_line_balanced AFTER INSERT ON journal_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION journal_entry_balanced();

-- ---------------------------------------------------------------------
-- Fee set-up (FR 10.1–10.4)
-- ---------------------------------------------------------------------
CREATE TABLE fee_head (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id      BIGINT         NOT NULL REFERENCES school (id),
    name           VARCHAR(80)    NOT NULL,
    frequency      VARCHAR(10)    NOT NULL CHECK (frequency IN ('MONTHLY', 'YEARLY', 'ONE_TIME', 'PER_TERM', 'CUSTOM')),
    max_amount     NUMERIC(14, 2) CHECK (max_amount IS NULL OR max_amount >= 0),
    display_order  INT            NOT NULL DEFAULT 0,
    refundable     BOOLEAN        NOT NULL DEFAULT false,
    system_code    VARCHAR(20),
    status         VARCHAR(10)    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version        BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT fee_head_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT fee_head_system_uq UNIQUE (school_id, system_code)
);
CREATE UNIQUE INDEX fee_head_name_uq ON fee_head (school_id, lower(name));

CREATE TABLE fee_structure (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT         NOT NULL REFERENCES school (id),
    academic_year_id  BIGINT         NOT NULL,
    fee_head_id       BIGINT         NOT NULL,
    class_id          BIGINT         NOT NULL,
    amount            NUMERIC(14, 2) NOT NULL CHECK (amount >= 0),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT fee_structure_uq UNIQUE (school_id, academic_year_id, fee_head_id, class_id),
    CONSTRAINT fee_structure_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id),
    CONSTRAINT fee_structure_head_fk FOREIGN KEY (school_id, fee_head_id) REFERENCES fee_head (school_id, id),
    CONSTRAINT fee_structure_class_fk FOREIGN KEY (school_id, class_id) REFERENCES school_class (school_id, id)
);

CREATE TABLE student_fee_override (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT         NOT NULL REFERENCES school (id),
    student_id        BIGINT         NOT NULL,
    academic_year_id  BIGINT         NOT NULL,
    fee_head_id       BIGINT         NOT NULL,
    amount            NUMERIC(14, 2) NOT NULL CHECK (amount >= 0),
    reason            VARCHAR(500)   NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT student_fee_override_uq UNIQUE (school_id, student_id, academic_year_id, fee_head_id),
    CONSTRAINT student_fee_override_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id),
    CONSTRAINT student_fee_override_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id),
    CONSTRAINT student_fee_override_head_fk FOREIGN KEY (school_id, fee_head_id) REFERENCES fee_head (school_id, id)
);

CREATE TABLE discount (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id      BIGINT         NOT NULL REFERENCES school (id),
    student_id     BIGINT         NOT NULL,
    fee_head_id    BIGINT,
    kind           VARCHAR(10)    NOT NULL CHECK (kind IN ('AMOUNT', 'PERCENT')),
    value          NUMERIC(14, 2) NOT NULL CHECK (value > 0),
    reason         VARCHAR(500)   NOT NULL,
    valid_from     DATE           NOT NULL,
    valid_to       DATE,
    status         VARCHAR(10)    NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'ENDED')),
    requested_by   VARCHAR(200),
    decided_by     VARCHAR(200),
    decided_at     TIMESTAMPTZ,
    decision_note  VARCHAR(500),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT discount_percent_max CHECK (kind <> 'PERCENT' OR value <= 100),
    CONSTRAINT discount_dates CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT discount_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id),
    CONSTRAINT discount_head_fk FOREIGN KEY (school_id, fee_head_id) REFERENCES fee_head (school_id, id)
);
CREATE INDEX discount_student_idx ON discount (school_id, student_id);

CREATE TABLE sibling_discount_rule (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id     BIGINT        NOT NULL REFERENCES school (id),
    child_order   INT           NOT NULL CHECK (child_order >= 2),
    percent       NUMERIC(5, 2) NOT NULL CHECK (percent > 0 AND percent <= 100),
    fee_head_ids  BIGINT[]      NOT NULL DEFAULT '{}',
    CONSTRAINT sibling_discount_rule_uq UNIQUE (school_id, child_order)
);

-- ---------------------------------------------------------------------
-- Bill runs, bills and lines (FR 10.5, 10.11)
-- ---------------------------------------------------------------------
CREATE TABLE fee_run (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT       NOT NULL REFERENCES school (id),
    kind              VARCHAR(10)  NOT NULL CHECK (kind IN ('BILLS', 'LATE_FEE')),
    academic_year_id  BIGINT       NOT NULL,
    idempotency_key   VARCHAR(100) NOT NULL,
    parameters        JSONB        NOT NULL,
    status            VARCHAR(10)  NOT NULL CHECK (status IN ('RUNNING', 'DONE')),
    report            JSONB,
    created_by        VARCHAR(200),
    started_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    CONSTRAINT fee_run_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT fee_run_key_uq UNIQUE (school_id, idempotency_key),
    CONSTRAINT fee_run_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id)
);

CREATE TABLE bill (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT       NOT NULL REFERENCES school (id),
    bill_no           VARCHAR(20)  NOT NULL,
    student_id        BIGINT       NOT NULL,
    family_id         BIGINT       NOT NULL,
    academic_year_id  BIGINT       NOT NULL,
    class_id          BIGINT,
    section_id        BIGINT,
    title             VARCHAR(200) NOT NULL,
    issue_date        DATE         NOT NULL,
    due_date          DATE         NOT NULL,
    validity_date     DATE         NOT NULL,
    fee_run_id        BIGINT,
    created_by        VARCHAR(200),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT bill_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT bill_no_uq UNIQUE (school_id, bill_no),
    CONSTRAINT bill_dates CHECK (due_date >= issue_date AND validity_date >= due_date),
    CONSTRAINT bill_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id),
    CONSTRAINT bill_family_fk FOREIGN KEY (school_id, family_id) REFERENCES family (school_id, id),
    CONSTRAINT bill_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id),
    CONSTRAINT bill_run_fk FOREIGN KEY (school_id, fee_run_id) REFERENCES fee_run (school_id, id)
);
CREATE INDEX bill_student_idx ON bill (school_id, student_id);
CREATE INDEX bill_family_idx ON bill (school_id, family_id);

CREATE TABLE bill_line (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id      BIGINT         NOT NULL REFERENCES school (id),
    bill_id        BIGINT         NOT NULL,
    student_id     BIGINT         NOT NULL,
    fee_head_id    BIGINT         NOT NULL,
    period_key     VARCHAR(40)    NOT NULL,
    description    VARCHAR(200)   NOT NULL,
    gross          NUMERIC(14, 2) NOT NULL CHECK (gross >= 0),
    discount       NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (discount >= 0),
    net            NUMERIC(14, 2) NOT NULL,
    discount_note  VARCHAR(300),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT bill_line_net CHECK (net = gross - discount AND net >= 0),
    CONSTRAINT bill_line_school_id_uq UNIQUE (school_id, id),
    -- No student is ever charged the same head twice for the same period (idempotent bill runs, FR 10.5).
    CONSTRAINT bill_line_once UNIQUE (school_id, student_id, fee_head_id, period_key),
    CONSTRAINT bill_line_bill_fk FOREIGN KEY (school_id, bill_id) REFERENCES bill (school_id, id),
    CONSTRAINT bill_line_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id),
    CONSTRAINT bill_line_head_fk FOREIGN KEY (school_id, fee_head_id) REFERENCES fee_head (school_id, id)
);
CREATE INDEX bill_line_bill_idx ON bill_line (bill_id);

-- Corrections to a charged line (FR 10.13). Effective only once approved.
CREATE TABLE fee_adjustment (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id     BIGINT         NOT NULL REFERENCES school (id),
    bill_line_id  BIGINT         NOT NULL,
    amount        NUMERIC(14, 2) NOT NULL CHECK (amount <> 0),
    kind          VARCHAR(12)    NOT NULL CHECK (kind IN ('CORRECTION', 'WAIVER', 'BAD_DEBT')),
    reason        VARCHAR(500)   NOT NULL,
    requested_by  VARCHAR(200),
    requested_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT fee_adjustment_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT fee_adjustment_line_fk FOREIGN KEY (school_id, bill_line_id) REFERENCES bill_line (school_id, id)
);
CREATE INDEX fee_adjustment_line_idx ON fee_adjustment (bill_line_id);

CREATE TABLE fee_adjustment_decision (
    adjustment_id  BIGINT       PRIMARY KEY,
    school_id      BIGINT       NOT NULL REFERENCES school (id),
    decision       VARCHAR(10)  NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    decided_by     VARCHAR(200),
    note           VARCHAR(500),
    decided_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fee_adjustment_decision_fk FOREIGN KEY (school_id, adjustment_id) REFERENCES fee_adjustment (school_id, id)
);

-- ---------------------------------------------------------------------
-- Payments and allocations (FR 10.7–10.10, 10.18)
-- ---------------------------------------------------------------------
CREATE TABLE payment (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id            BIGINT         NOT NULL REFERENCES school (id),
    receipt_no           VARCHAR(30),
    family_id            BIGINT         NOT NULL,
    student_id           BIGINT,
    academic_year_id     BIGINT         NOT NULL,
    payment_date         DATE           NOT NULL,
    method               VARCHAR(10)    NOT NULL CHECK (method IN ('CASH', 'BANK', 'CHEQUE', 'ONLINE')),
    ledger_account_id    BIGINT         NOT NULL,
    amount               NUMERIC(14, 2) NOT NULL CHECK (amount <> 0),
    reference            VARCHAR(100),
    remarks              VARCHAR(500),
    received_by          VARCHAR(200),
    idempotency_key      VARCHAR(100),
    request_hash         VARCHAR(64),
    reverses_payment_id  BIGINT,
    reversal_reason      VARCHAR(500),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT payment_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT payment_receipt_uq UNIQUE (school_id, receipt_no),
    CONSTRAINT payment_key_uq UNIQUE (school_id, idempotency_key),
    CONSTRAINT payment_reversed_once UNIQUE (school_id, reverses_payment_id),
    CONSTRAINT payment_kind CHECK ((reverses_payment_id IS NULL AND amount > 0 AND receipt_no IS NOT NULL)
                                OR (reverses_payment_id IS NOT NULL AND amount < 0 AND reversal_reason IS NOT NULL)),
    CONSTRAINT payment_family_fk FOREIGN KEY (school_id, family_id) REFERENCES family (school_id, id),
    CONSTRAINT payment_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id),
    CONSTRAINT payment_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id),
    CONSTRAINT payment_account_fk FOREIGN KEY (school_id, ledger_account_id) REFERENCES ledger_account (school_id, id),
    CONSTRAINT payment_reverses_fk FOREIGN KEY (school_id, reverses_payment_id) REFERENCES payment (school_id, id)
);
CREATE INDEX payment_family_idx ON payment (school_id, family_id);
CREATE INDEX payment_date_idx ON payment (school_id, payment_date);

-- How each payment settles bill lines. CREDIT allocations apply an earlier payment's unallocated part (advance) later.
CREATE TABLE payment_allocation (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT         NOT NULL REFERENCES school (id),
    payment_id        BIGINT         NOT NULL,
    bill_line_id      BIGINT         NOT NULL,
    amount            NUMERIC(14, 2) NOT NULL CHECK (amount <> 0),
    -- RELEASE: part of a payment taken back from a line whose charge was reduced after payment (becomes credit).
    kind              VARCHAR(10)    NOT NULL CHECK (kind IN ('PAYMENT', 'CREDIT', 'REVERSAL', 'RELEASE')),
    allocation_date   DATE           NOT NULL,
    journal_entry_id  BIGINT,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT payment_allocation_payment_fk FOREIGN KEY (school_id, payment_id) REFERENCES payment (school_id, id),
    CONSTRAINT payment_allocation_line_fk FOREIGN KEY (school_id, bill_line_id) REFERENCES bill_line (school_id, id),
    CONSTRAINT payment_allocation_journal_fk FOREIGN KEY (school_id, journal_entry_id) REFERENCES journal_entry (school_id, id)
);
CREATE INDEX payment_allocation_payment_idx ON payment_allocation (payment_id);
CREATE INDEX payment_allocation_line_idx ON payment_allocation (bill_line_id);

-- ---------------------------------------------------------------------
-- Defaulter follow-up (FR 10.16)
-- ---------------------------------------------------------------------
CREATE TABLE fee_followup (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id   BIGINT        NOT NULL REFERENCES school (id),
    family_id   BIGINT        NOT NULL,
    remark      VARCHAR(1000) NOT NULL,
    next_date   DATE,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fee_followup_family_fk FOREIGN KEY (school_id, family_id) REFERENCES family (school_id, id)
);
CREATE INDEX fee_followup_family_idx ON fee_followup (school_id, family_id);

-- ---------------------------------------------------------------------
-- Message outbox: written in the business transaction, sent and retried afterwards (receipts, reminders)
-- ---------------------------------------------------------------------
CREATE TABLE message_outbox (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id        BIGINT        NOT NULL REFERENCES school (id),
    channel          VARCHAR(10)   NOT NULL CHECK (channel IN ('SMS', 'EMAIL')),
    recipient        VARCHAR(254)  NOT NULL,
    subject          VARCHAR(200),
    body             VARCHAR(2000) NOT NULL,
    purpose          VARCHAR(30)   NOT NULL,
    source_type      VARCHAR(30),
    source_id        BIGINT,
    idempotency_key  VARCHAR(100),
    status           VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts         INT           NOT NULL DEFAULT 0,
    last_error       VARCHAR(500),
    next_attempt_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    sent_at          TIMESTAMPTZ
);
CREATE INDEX message_outbox_pending_idx ON message_outbox (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX message_outbox_source_idx ON message_outbox (school_id, source_type, source_id);
-- A retried "send reminders" request does not message the same person twice.
CREATE UNIQUE INDEX message_outbox_key_uq ON message_outbox (school_id, idempotency_key, recipient)
    WHERE idempotency_key IS NOT NULL;

-- ---------------------------------------------------------------------
-- Computed balances. security_invoker keeps row-level security in force for the querying role.
-- Balance of a line = net + approved adjustments - allocations (never stored, DATA-MODEL §7).
-- ---------------------------------------------------------------------
CREATE VIEW fee_line_status WITH (security_invoker = true) AS
SELECT bl.id AS bill_line_id, bl.school_id, bl.bill_id, bl.student_id, b.family_id, b.academic_year_id,
       bl.fee_head_id, bl.period_key, bl.description, b.bill_no, b.issue_date, b.due_date, bl.gross, bl.discount,
       bl.net, coalesce(adj.total, 0) AS adjusted, coalesce(al.total, 0) AS paid,
       bl.net + coalesce(adj.total, 0) - coalesce(al.total, 0) AS balance
FROM bill_line bl
JOIN bill b ON b.id = bl.bill_id
LEFT JOIN LATERAL (SELECT sum(a.amount) AS total FROM fee_adjustment a
                   JOIN fee_adjustment_decision d ON d.adjustment_id = a.id AND d.decision = 'APPROVED'
                   WHERE a.bill_line_id = bl.id) adj ON true
LEFT JOIN LATERAL (SELECT sum(pa.amount) AS total FROM payment_allocation pa
                   WHERE pa.bill_line_id = bl.id) al ON true;

-- Unused part of each payment (advance credit, FR 10.10). Reversed payments carry no credit.
CREATE VIEW payment_credit WITH (security_invoker = true) AS
SELECT p.id AS payment_id, p.school_id, p.family_id, p.student_id, p.payment_date, p.receipt_no, p.amount,
       coalesce(al.total, 0) AS allocated, p.amount - coalesce(al.total, 0) AS remaining
FROM payment p
LEFT JOIN LATERAL (SELECT sum(pa.amount) AS total FROM payment_allocation pa WHERE pa.payment_id = p.id) al ON true
WHERE p.reverses_payment_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM payment r WHERE r.reverses_payment_id = p.id);

-- ---------------------------------------------------------------------
-- Defaults for existing schools (new schools get them at creation)
-- ---------------------------------------------------------------------
INSERT INTO ledger_account (school_id, code, name, account_type, subtype, display_order)
SELECT s.id, v.code, v.name, v.type, v.subtype, v.ord
FROM school s
CROSS JOIN (VALUES ('1000', 'Cash in hand', 'ASSET', 'CASH', 1),
                   ('1010', 'Bank account', 'ASSET', 'BANK', 2),
                   ('2100', 'Fees received in advance', 'LIABILITY', 'STUDENT_ADVANCE', 3),
                   ('4000', 'Fee income', 'INCOME', 'FEE_INCOME', 4)) AS v(code, name, type, subtype, ord);

INSERT INTO fee_head (school_id, name, frequency, display_order, system_code)
SELECT s.id, 'Late fee', 'CUSTOM', 1000, 'LATE_FEE' FROM school s;

-- ---------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['ledger_account', 'journal_entry', 'journal_line', 'fee_head', 'fee_structure',
                             'student_fee_override', 'discount', 'sibling_discount_rule', 'fee_run', 'bill',
                             'bill_line', 'fee_adjustment', 'fee_adjustment_decision', 'payment',
                             'payment_allocation', 'fee_followup', 'message_outbox']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I
                            USING (school_id = app_current_school() OR app_all_schools())
                            WITH CHECK (school_id = app_current_school() OR app_all_schools())', t);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------
-- Grants: money history is insert-only (QA-FEE-022). Set-up tables and runs may change.
-- ---------------------------------------------------------------------
GRANT SELECT, INSERT ON journal_entry, journal_line, bill, bill_line, fee_adjustment, fee_adjustment_decision,
    payment, payment_allocation, fee_followup TO sms_app;
GRANT SELECT, INSERT, UPDATE ON ledger_account, fee_head, fee_run, message_outbox TO sms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON fee_structure, student_fee_override, discount, sibling_discount_rule TO sms_app;
GRANT SELECT ON fee_line_status, payment_credit TO sms_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO sms_app;
