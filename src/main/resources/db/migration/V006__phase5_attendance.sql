-- =====================================================================
-- V006 Phase 5: attendance (FR 13), leave applications, staff monthly summaries (feed payroll), absent fine head.
-- Statuses (DECISIONS D-09): P present, L late, HD half day, A absent, LV approved leave.
-- =====================================================================

CREATE TABLE student_attendance (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT        NOT NULL REFERENCES school (id),
    academic_year_id  BIGINT        NOT NULL,
    att_date          DATE          NOT NULL,
    section_id        BIGINT        NOT NULL,
    student_id        BIGINT        NOT NULL,
    status            VARCHAR(2)    NOT NULL CHECK (status IN ('P', 'L', 'HD', 'A', 'LV')),
    arrival_time      TIME,
    note              VARCHAR(300),
    marked_by         VARCHAR(200),
    marked_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT student_attendance_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT student_attendance_once UNIQUE (school_id, student_id, att_date),
    CONSTRAINT student_attendance_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id),
    CONSTRAINT student_attendance_section_fk FOREIGN KEY (school_id, section_id) REFERENCES section (school_id, id),
    CONSTRAINT student_attendance_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id)
);
CREATE INDEX student_attendance_section_idx ON student_attendance (school_id, section_id, att_date);

-- A register counts as taken once saved (missing-register list, FR 13.2).
CREATE TABLE register_completion (
    school_id     BIGINT       NOT NULL REFERENCES school (id),
    section_id    BIGINT       NOT NULL,
    att_date      DATE         NOT NULL,
    completed_by  VARCHAR(200),
    completed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (school_id, section_id, att_date),
    CONSTRAINT register_completion_section_fk FOREIGN KEY (school_id, section_id) REFERENCES section (school_id, id)
);

CREATE TABLE staff_attendance (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id     BIGINT        NOT NULL REFERENCES school (id),
    att_date      DATE          NOT NULL,
    staff_id      BIGINT        NOT NULL,
    status        VARCHAR(2)    NOT NULL CHECK (status IN ('P', 'L', 'HD', 'A', 'LV')),
    arrival_time  TIME,
    note          VARCHAR(300),
    marked_by     VARCHAR(200),
    marked_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT staff_attendance_once UNIQUE (school_id, staff_id, att_date),
    CONSTRAINT staff_attendance_staff_fk FOREIGN KEY (school_id, staff_id) REFERENCES staff (school_id, id)
);

-- Every change to an already-saved status (FR 13.3, 13.10). Insert-only.
CREATE TABLE attendance_correction (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id     BIGINT        NOT NULL REFERENCES school (id),
    person_type   VARCHAR(10)   NOT NULL CHECK (person_type IN ('STUDENT', 'STAFF')),
    person_id     BIGINT        NOT NULL,
    att_date      DATE          NOT NULL,
    old_status    VARCHAR(2),
    new_status    VARCHAR(2),
    reason        VARCHAR(500)  NOT NULL,
    corrected_by  VARCHAR(200),
    corrected_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX attendance_correction_person_idx ON attendance_correction (school_id, person_type, person_id);

-- Leave entered by the office (no portal, D-19). Approved leave turns those days into LV (FR 13.8).
CREATE TABLE leave_application (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id       BIGINT        NOT NULL REFERENCES school (id),
    person_type     VARCHAR(10)   NOT NULL CHECK (person_type IN ('STUDENT', 'STAFF')),
    student_id      BIGINT,
    staff_id        BIGINT,
    leave_type      VARCHAR(10)   NOT NULL CHECK (leave_type IN ('SICK', 'CASUAL', 'FAMILY', 'OFFICIAL', 'OTHER')),
    date_from       DATE          NOT NULL,
    date_to         DATE          NOT NULL,
    working_days    INT           NOT NULL,
    reason          VARCHAR(500)  NOT NULL,
    status          VARCHAR(10)   NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    entered_by      VARCHAR(200),
    entered_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_by      VARCHAR(200),
    decided_at      TIMESTAMPTZ,
    decision_note   VARCHAR(500),
    CONSTRAINT leave_dates CHECK (date_to >= date_from),
    CONSTRAINT leave_person CHECK ((person_type = 'STUDENT' AND student_id IS NOT NULL AND staff_id IS NULL)
                                OR (person_type = 'STAFF' AND staff_id IS NOT NULL AND student_id IS NULL)),
    CONSTRAINT leave_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id),
    CONSTRAINT leave_staff_fk FOREIGN KEY (school_id, staff_id) REFERENCES staff (school_id, id)
);
CREATE INDEX leave_application_dates_idx ON leave_application (school_id, date_from, date_to);

-- Approved monthly staff totals; payroll uses only these (QA-ATT-010). An approved month is locked.
CREATE TABLE staff_month_summary (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id     BIGINT        NOT NULL REFERENCES school (id),
    staff_id      BIGINT        NOT NULL,
    month         VARCHAR(7)    NOT NULL CHECK (month ~ '^\d{4}-\d{2}$'),
    working_days  INT           NOT NULL,
    present_days  INT           NOT NULL,
    late_days     INT           NOT NULL,
    half_days     INT           NOT NULL,
    absent_days   INT           NOT NULL,
    leave_days    INT           NOT NULL,
    unmarked_days INT           NOT NULL,
    approved_by   VARCHAR(200),
    approved_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT staff_month_summary_uq UNIQUE (school_id, staff_id, month),
    CONSTRAINT staff_month_summary_staff_fk FOREIGN KEY (school_id, staff_id) REFERENCES staff (school_id, id)
);

-- Absent fine (FR 10.12): a built-in fee head like the late fee, run like the late fee
ALTER TABLE fee_run DROP CONSTRAINT fee_run_kind_check;
ALTER TABLE fee_run ALTER COLUMN kind TYPE VARCHAR(20);
ALTER TABLE fee_run ADD CONSTRAINT fee_run_kind_check CHECK (kind IN ('BILLS', 'LATE_FEE', 'ABSENT_FINE'));
INSERT INTO fee_head (school_id, name, frequency, display_order, system_code)
SELECT s.id, 'Absent fine', 'CUSTOM', 1001, 'ABSENT_FINE' FROM school s;

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['student_attendance', 'register_completion', 'staff_attendance', 'attendance_correction',
                             'leave_application', 'staff_month_summary']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I
                            USING (school_id = app_current_school() OR app_all_schools())
                            WITH CHECK (school_id = app_current_school() OR app_all_schools())', t);
    END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE ON student_attendance, staff_attendance, leave_application TO sms_app;
GRANT SELECT, INSERT, UPDATE ON register_completion TO sms_app;
GRANT SELECT, INSERT ON attendance_correction TO sms_app;
-- Reopening an approved month (before payroll is approved) removes its summary; audited.
GRANT SELECT, INSERT, DELETE ON staff_month_summary TO sms_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO sms_app;
