-- =====================================================================
-- V005 Phase 4: school accounts (FR 11) and payroll (FR 6.8, 13.12, DECISIONS D-04).
-- Every money movement is a balanced journal entry. Entries, decisions, day closes and journal lines are insert-only
-- for the app; corrections are reversals with a reason.
-- =====================================================================

-- More kinds of ledger account
ALTER TABLE ledger_account DROP CONSTRAINT ledger_account_subtype_check;
ALTER TABLE ledger_account ADD CONSTRAINT ledger_account_subtype_check CHECK (subtype IN (
    'CASH', 'BANK', 'FEE_INCOME', 'STUDENT_ADVANCE', 'OTHER', 'OPENING', 'INCOME_HEAD', 'EXPENSE_HEAD',
    'SALARY_EXPENSE', 'SALARY_PAYABLE', 'TAX_PAYABLE', 'VENDOR_PAYABLE', 'SALARY_DEDUCTION'));
ALTER TABLE ledger_account ADD COLUMN bank_details VARCHAR(200);

ALTER TABLE journal_line ADD CONSTRAINT journal_line_school_id_uq UNIQUE (school_id, id);
CREATE INDEX journal_line_account_idx ON journal_line (school_id, ledger_account_id);
CREATE INDEX journal_entry_date_idx ON journal_entry (school_id, entry_date);

-- ---------------------------------------------------------------------
-- Categories ("heads") for other income and expenses (FR 11.6): each has its own ledger account
-- ---------------------------------------------------------------------
CREATE TABLE account_category (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id          BIGINT       NOT NULL REFERENCES school (id),
    name               VARCHAR(80)  NOT NULL,
    kind               VARCHAR(10)  NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE')),
    ledger_account_id  BIGINT       NOT NULL,
    system_code        VARCHAR(20),
    status             VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT account_category_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT account_category_account_fk FOREIGN KEY (school_id, ledger_account_id) REFERENCES ledger_account (school_id, id)
);
CREATE UNIQUE INDEX account_category_name_uq ON account_category (school_id, kind, lower(name));

CREATE TABLE vendor (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id   BIGINT        NOT NULL REFERENCES school (id),
    name        VARCHAR(200)  NOT NULL,
    phone       VARCHAR(30),
    address     VARCHAR(500),
    notes       VARCHAR(500),
    status      VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT vendor_school_id_uq UNIQUE (school_id, id)
);
CREATE UNIQUE INDEX vendor_name_uq ON vendor (school_id, lower(name));

-- ---------------------------------------------------------------------
-- Money entries other than fees (FR 11.2, 11.4, 11.5, 11.7). Effective once a decision row says APPROVED.
-- ---------------------------------------------------------------------
CREATE TABLE money_entry (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id           BIGINT         NOT NULL REFERENCES school (id),
    entry_no            VARCHAR(20)    NOT NULL,
    kind                VARCHAR(15)    NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE', 'TRANSFER', 'PURCHASE', 'VENDOR_PAYMENT')),
    entry_date          DATE           NOT NULL,
    academic_year_id    BIGINT         NOT NULL,
    category_id         BIGINT,
    account_id          BIGINT,
    to_account_id       BIGINT,
    vendor_id           BIGINT,
    amount              NUMERIC(14, 2) NOT NULL CHECK (amount > 0),
    reference           VARCHAR(100),
    description         VARCHAR(500),
    attachment_file_id  BIGINT,
    needs_approval      BOOLEAN        NOT NULL DEFAULT false,
    idempotency_key     VARCHAR(100),
    reverses_entry_id   BIGINT,
    reversal_reason     VARCHAR(500),
    created_by          VARCHAR(200),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT money_entry_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT money_entry_no_uq UNIQUE (school_id, entry_no),
    CONSTRAINT money_entry_reversed_once UNIQUE (school_id, reverses_entry_id),
    CONSTRAINT money_entry_key_uq UNIQUE (school_id, idempotency_key),
    CONSTRAINT money_entry_shape CHECK (
        (kind = 'INCOME' AND category_id IS NOT NULL AND account_id IS NOT NULL)
     OR (kind = 'EXPENSE' AND category_id IS NOT NULL AND account_id IS NOT NULL)
     OR (kind = 'TRANSFER' AND account_id IS NOT NULL AND to_account_id IS NOT NULL AND account_id <> to_account_id)
     OR (kind = 'PURCHASE' AND category_id IS NOT NULL AND vendor_id IS NOT NULL)
     OR (kind = 'VENDOR_PAYMENT' AND vendor_id IS NOT NULL AND account_id IS NOT NULL)),
    CONSTRAINT money_entry_reversal CHECK (reverses_entry_id IS NULL OR reversal_reason IS NOT NULL),
    CONSTRAINT money_entry_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id),
    CONSTRAINT money_entry_category_fk FOREIGN KEY (school_id, category_id) REFERENCES account_category (school_id, id),
    CONSTRAINT money_entry_account_fk FOREIGN KEY (school_id, account_id) REFERENCES ledger_account (school_id, id),
    CONSTRAINT money_entry_to_account_fk FOREIGN KEY (school_id, to_account_id) REFERENCES ledger_account (school_id, id),
    CONSTRAINT money_entry_vendor_fk FOREIGN KEY (school_id, vendor_id) REFERENCES vendor (school_id, id),
    CONSTRAINT money_entry_file_fk FOREIGN KEY (school_id, attachment_file_id) REFERENCES stored_file (school_id, id),
    CONSTRAINT money_entry_reverses_fk FOREIGN KEY (school_id, reverses_entry_id) REFERENCES money_entry (school_id, id)
);
CREATE INDEX money_entry_date_idx ON money_entry (school_id, entry_date);
CREATE INDEX money_entry_vendor_idx ON money_entry (school_id, vendor_id);

CREATE TABLE money_entry_decision (
    entry_id          BIGINT       PRIMARY KEY,
    school_id         BIGINT       NOT NULL REFERENCES school (id),
    decision          VARCHAR(10)  NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    decided_by        VARCHAR(200),
    note              VARCHAR(500),
    journal_entry_id  BIGINT,
    decided_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT money_entry_decision_entry_fk FOREIGN KEY (school_id, entry_id) REFERENCES money_entry (school_id, id),
    CONSTRAINT money_entry_decision_journal_fk FOREIGN KEY (school_id, journal_entry_id) REFERENCES journal_entry (school_id, id)
);

-- ---------------------------------------------------------------------
-- Day close (FR 11.9) and bank reconciliation (FR 11.10)
-- ---------------------------------------------------------------------
CREATE TABLE day_close (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id          BIGINT         NOT NULL REFERENCES school (id),
    ledger_account_id  BIGINT         NOT NULL,
    close_date         DATE           NOT NULL,
    opening            NUMERIC(14, 2) NOT NULL,
    money_in           NUMERIC(14, 2) NOT NULL,
    money_out          NUMERIC(14, 2) NOT NULL,
    closing            NUMERIC(14, 2) NOT NULL,
    counted            NUMERIC(14, 2) NOT NULL,
    difference         NUMERIC(14, 2) NOT NULL,
    note               VARCHAR(500),
    closed_by          VARCHAR(200),
    closed_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT day_close_uq UNIQUE (school_id, ledger_account_id, close_date),
    CONSTRAINT day_close_note CHECK (difference = 0 OR note IS NOT NULL),
    CONSTRAINT day_close_account_fk FOREIGN KEY (school_id, ledger_account_id) REFERENCES ledger_account (school_id, id)
);

CREATE TABLE bank_clearance (
    journal_line_id  BIGINT       PRIMARY KEY,
    school_id        BIGINT       NOT NULL REFERENCES school (id),
    cleared_on       DATE         NOT NULL,
    cleared_by       VARCHAR(200),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT bank_clearance_line_fk FOREIGN KEY (school_id, journal_line_id) REFERENCES journal_line (school_id, id)
);

-- ---------------------------------------------------------------------
-- Payroll (DECISIONS D-04)
-- ---------------------------------------------------------------------
CREATE TABLE salary_sheet (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id            BIGINT       NOT NULL REFERENCES school (id),
    month                VARCHAR(7)   NOT NULL CHECK (month ~ '^\d{4}-\d{2}$'),
    status               VARCHAR(10)  NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'PAID', 'REVERSED')),
    working_days         INT          NOT NULL CHECK (working_days > 0),
    created_by           VARCHAR(200),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    approved_by          VARCHAR(200),
    approved_at          TIMESTAMPTZ,
    approve_journal_id   BIGINT,
    paid_by              VARCHAR(200),
    paid_at              TIMESTAMPTZ,
    payment_account_id   BIGINT,
    payment_date         DATE,
    pay_idempotency_key  VARCHAR(100),
    pay_journal_id       BIGINT,
    reversed_by          VARCHAR(200),
    reversed_at          TIMESTAMPTZ,
    reversal_reason      VARCHAR(500),
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT salary_sheet_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT salary_sheet_account_fk FOREIGN KEY (school_id, payment_account_id) REFERENCES ledger_account (school_id, id),
    CONSTRAINT salary_sheet_approve_fk FOREIGN KEY (school_id, approve_journal_id) REFERENCES journal_entry (school_id, id),
    CONSTRAINT salary_sheet_pay_fk FOREIGN KEY (school_id, pay_journal_id) REFERENCES journal_entry (school_id, id)
);
-- One live sheet per month; a reversed sheet can be replaced.
CREATE UNIQUE INDEX salary_sheet_month_uq ON salary_sheet (school_id, month) WHERE status <> 'REVERSED';

CREATE TABLE salary_sheet_line (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id             BIGINT         NOT NULL REFERENCES school (id),
    sheet_id              BIGINT         NOT NULL,
    staff_id              BIGINT         NOT NULL,
    basic                 NUMERIC(14, 2) NOT NULL CHECK (basic >= 0),
    per_lecture_rate      NUMERIC(14, 2),
    lectures              INT            NOT NULL DEFAULT 0 CHECK (lectures >= 0),
    working_days          INT            NOT NULL,
    absent_days           INT            NOT NULL DEFAULT 0 CHECK (absent_days >= 0),
    half_days             INT            NOT NULL DEFAULT 0 CHECK (half_days >= 0),
    late_days             INT            NOT NULL DEFAULT 0 CHECK (late_days >= 0),
    leave_days            INT            NOT NULL DEFAULT 0 CHECK (leave_days >= 0),
    deduction_days        NUMERIC(6, 2)  NOT NULL DEFAULT 0,
    attendance_deduction  NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (attendance_deduction >= 0),
    allowance             NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (allowance >= 0),
    tax                   NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (tax >= 0),
    other_deduction       NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (other_deduction >= 0),
    other_note            VARCHAR(300),
    earned                NUMERIC(14, 2) NOT NULL CHECK (earned >= 0),
    net                   NUMERIC(14, 2) NOT NULL CHECK (net >= 0),
    breakdown             JSONB          NOT NULL DEFAULT '{}',
    attendance_source     VARCHAR(10)    NOT NULL DEFAULT 'MANUAL' CHECK (attendance_source IN ('MANUAL', 'ATTENDANCE')),
    CONSTRAINT salary_sheet_line_uq UNIQUE (school_id, sheet_id, staff_id),
    CONSTRAINT salary_sheet_line_sheet_fk FOREIGN KEY (school_id, sheet_id) REFERENCES salary_sheet (school_id, id),
    CONSTRAINT salary_sheet_line_staff_fk FOREIGN KEY (school_id, staff_id) REFERENCES staff (school_id, id)
);

-- An approved, paid or reversed sheet is locked: its lines cannot change (QA-PAY-003).
CREATE FUNCTION salary_line_locked() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT status FROM salary_sheet WHERE id = coalesce(NEW.sheet_id, OLD.sheet_id)) <> 'DRAFT' THEN
        RAISE EXCEPTION 'Salary sheet is locked';
    END IF;
    RETURN coalesce(NEW, OLD);
END
$$;
CREATE TRIGGER salary_sheet_line_locked BEFORE INSERT OR UPDATE OR DELETE ON salary_sheet_line
    FOR EACH ROW EXECUTE FUNCTION salary_line_locked();

-- ---------------------------------------------------------------------
-- Defaults for existing schools (new schools get them at creation)
-- ---------------------------------------------------------------------
INSERT INTO ledger_account (school_id, code, name, account_type, subtype, display_order)
SELECT s.id, v.code, v.name, v.type, v.subtype, v.ord
FROM school s
CROSS JOIN (VALUES ('3000', 'Opening balances', 'EQUITY', 'OPENING', 10),
                   ('2200', 'Salaries payable', 'LIABILITY', 'SALARY_PAYABLE', 11),
                   ('2300', 'Tax deducted from salaries', 'LIABILITY', 'TAX_PAYABLE', 12),
                   ('2400', 'Owed to suppliers', 'LIABILITY', 'VENDOR_PAYABLE', 13),
                   ('4900', 'Deductions from salaries', 'INCOME', 'SALARY_DEDUCTION', 14),
                   ('5000', 'Salaries', 'EXPENSE', 'SALARY_EXPENSE', 15)) AS v(code, name, type, subtype, ord);

INSERT INTO ledger_account (school_id, code, name, account_type, subtype, display_order)
SELECT s.id, v.code, v.name, v.type, v.subtype, v.ord
FROM school s
CROSS JOIN (VALUES ('4100', 'Donations', 'INCOME', 'INCOME_HEAD', 20),
                   ('4110', 'Canteen', 'INCOME', 'INCOME_HEAD', 21),
                   ('4190', 'Other income', 'INCOME', 'INCOME_HEAD', 22),
                   ('5100', 'Utilities', 'EXPENSE', 'EXPENSE_HEAD', 30),
                   ('5110', 'Stationery and supplies', 'EXPENSE', 'EXPENSE_HEAD', 31),
                   ('5120', 'Repairs and maintenance', 'EXPENSE', 'EXPENSE_HEAD', 32),
                   ('5130', 'Rent', 'EXPENSE', 'EXPENSE_HEAD', 33),
                   ('5190', 'Other expenses', 'EXPENSE', 'EXPENSE_HEAD', 34)) AS v(code, name, type, subtype, ord);

INSERT INTO account_category (school_id, name, kind, ledger_account_id, system_code)
SELECT a.school_id, a.name, CASE WHEN a.account_type = 'INCOME' THEN 'INCOME' ELSE 'EXPENSE' END, a.id,
       CASE WHEN a.subtype = 'SALARY_EXPENSE' THEN 'SALARIES' END
FROM ledger_account a WHERE a.subtype IN ('INCOME_HEAD', 'EXPENSE_HEAD', 'SALARY_EXPENSE');

-- ---------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['account_category', 'vendor', 'money_entry', 'money_entry_decision', 'day_close',
                             'bank_clearance', 'salary_sheet', 'salary_sheet_line']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I
                            USING (school_id = app_current_school() OR app_all_schools())
                            WITH CHECK (school_id = app_current_school() OR app_all_schools())', t);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------
GRANT SELECT, INSERT ON money_entry, money_entry_decision, day_close TO sms_app;
GRANT SELECT, INSERT, UPDATE ON account_category, vendor, salary_sheet TO sms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON salary_sheet_line TO sms_app;
-- A mistaken "cleared" mark can be removed (audited); nothing else about the bank line changes.
GRANT SELECT, INSERT, DELETE ON bank_clearance TO sms_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO sms_app;
