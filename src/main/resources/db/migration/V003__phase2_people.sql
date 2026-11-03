-- =====================================================================
-- V003 Phase 2: families, students, enrolments, admissions, staff, teaching assignments, timetable,
-- certificates, activities, number sequences. Every school-owned table: school_id + RLS + composite keys.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;     -- fuzzy name matching for duplicate detection (FR 4.5)
CREATE EXTENSION IF NOT EXISTS btree_gist;  -- no overlapping class enrolments per student (FR 5.4)

-- ---------------------------------------------------------------------
-- Per-school number sequences (family no., enquiry no., employee no., certificate serials)
-- ---------------------------------------------------------------------
CREATE TABLE number_sequence (
    school_id   BIGINT      NOT NULL REFERENCES school (id),
    name        VARCHAR(60) NOT NULL,
    next_value  BIGINT      NOT NULL DEFAULT 1,
    PRIMARY KEY (school_id, name)
);

-- ---------------------------------------------------------------------
-- Families and contacts (FR 8)
-- ---------------------------------------------------------------------
CREATE TABLE family (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id           BIGINT       NOT NULL REFERENCES school (id),
    family_no           VARCHAR(20)  NOT NULL,
    father_name         VARCHAR(200),
    father_cnic         VARCHAR(20),
    father_phone        VARCHAR(30),
    father_profession   VARCHAR(100),
    mother_name         VARCHAR(200),
    mother_cnic         VARCHAR(20),
    mother_phone        VARCHAR(30),
    email               VARCHAR(254),
    address             VARCHAR(500),
    emergency_name      VARCHAR(200),
    emergency_phone     VARCHAR(30),
    preferred_channel   VARCHAR(20)  NOT NULL DEFAULT 'SMS' CHECK (preferred_channel IN ('SMS', 'WHATSAPP', 'NONE')),
    remarks             VARCHAR(1000),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT family_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT family_no_uq UNIQUE (school_id, family_no)
);
CREATE INDEX family_father_phone_idx ON family (school_id, father_phone);
CREATE INDEX family_mother_phone_idx ON family (school_id, mother_phone);
CREATE INDEX family_father_cnic_idx ON family (school_id, father_cnic);
CREATE INDEX family_father_name_trgm ON family USING gin (lower(father_name) gin_trgm_ops);

-- ---------------------------------------------------------------------
-- Staff (FR 6, 7) and salary profile (FR 6.8)
-- ---------------------------------------------------------------------
CREATE TABLE staff (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id               BIGINT       NOT NULL REFERENCES school (id),
    employee_no             VARCHAR(20)  NOT NULL,
    category                VARCHAR(10)  NOT NULL CHECK (category IN ('TEACHER', 'OTHER')),
    name                    VARCHAR(200) NOT NULL,
    father_or_husband_name  VARCHAR(200),
    gender                  VARCHAR(10)  CHECK (gender IN ('MALE', 'FEMALE')),
    dob                     DATE,
    cnic                    VARCHAR(20),
    phone                   VARCHAR(30),
    email                   VARCHAR(254),
    address                 VARCHAR(500),
    qualification           VARCHAR(200),
    experience              VARCHAR(200),
    designation             VARCHAR(100) NOT NULL,
    employment_type         VARCHAR(20)  NOT NULL DEFAULT 'PERMANENT' CHECK (employment_type IN ('PERMANENT', 'CONTRACT')),
    joining_date            DATE         NOT NULL,
    status                  VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LEFT')),
    leaving_date            DATE,
    leaving_reason          VARCHAR(500),
    photo_file_id           BIGINT,
    notes                   VARCHAR(1000),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT staff_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT staff_employee_no_uq UNIQUE (school_id, employee_no),
    CONSTRAINT staff_photo_fk FOREIGN KEY (school_id, photo_file_id) REFERENCES stored_file (school_id, id)
);

CREATE TABLE staff_document (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id   BIGINT       NOT NULL REFERENCES school (id),
    staff_id    BIGINT       NOT NULL,
    doc_type    VARCHAR(40)  NOT NULL,
    title       VARCHAR(200) NOT NULL,
    file_id     BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT staff_document_staff_fk FOREIGN KEY (school_id, staff_id) REFERENCES staff (school_id, id),
    CONSTRAINT staff_document_file_fk FOREIGN KEY (school_id, file_id) REFERENCES stored_file (school_id, id)
);

-- Salary history: every increment is a new row (insert-only).
CREATE TABLE salary_profile (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT        NOT NULL REFERENCES school (id),
    staff_id          BIGINT        NOT NULL,
    effective_from    DATE          NOT NULL,
    basic_salary      NUMERIC(14,2) NOT NULL CHECK (basic_salary >= 0),
    per_lecture_rate  NUMERIC(14,2) CHECK (per_lecture_rate >= 0),
    bank_name         VARCHAR(100),
    bank_account      VARCHAR(60),
    reason            VARCHAR(300)  NOT NULL,
    created_by_name   VARCHAR(200),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT salary_profile_staff_fk FOREIGN KEY (school_id, staff_id) REFERENCES staff (school_id, id),
    CONSTRAINT salary_profile_uq UNIQUE (school_id, staff_id, effective_from)
);

-- A login for a staff member (FR 6.2, 7.2)
ALTER TABLE app_user ADD COLUMN staff_id BIGINT;
ALTER TABLE app_user ADD CONSTRAINT app_user_staff_fk FOREIGN KEY (school_id, staff_id) REFERENCES staff (school_id, id);
CREATE UNIQUE INDEX app_user_staff_uq ON app_user (school_id, staff_id) WHERE staff_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- Teaching assignments (FR 6.3, 6.6, 9.5): subject_id NULL = class teacher of the section
-- ---------------------------------------------------------------------
CREATE TABLE teaching_assignment (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT      NOT NULL REFERENCES school (id),
    academic_year_id  BIGINT      NOT NULL,
    section_id        BIGINT      NOT NULL,
    subject_id        BIGINT,
    staff_id          BIGINT      NOT NULL,
    effective_from    DATE        NOT NULL,
    effective_to      DATE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT teaching_assignment_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id),
    CONSTRAINT teaching_assignment_section_fk FOREIGN KEY (school_id, section_id) REFERENCES section (school_id, id),
    CONSTRAINT teaching_assignment_subject_fk FOREIGN KEY (school_id, subject_id) REFERENCES subject (school_id, id),
    CONSTRAINT teaching_assignment_staff_fk FOREIGN KEY (school_id, staff_id) REFERENCES staff (school_id, id),
    CONSTRAINT teaching_assignment_dates CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
-- One open class teacher per section, one open teacher per section+subject.
CREATE UNIQUE INDEX teaching_assignment_class_teacher_uq ON teaching_assignment (school_id, academic_year_id, section_id)
    WHERE subject_id IS NULL AND effective_to IS NULL;
CREATE UNIQUE INDEX teaching_assignment_subject_uq ON teaching_assignment (school_id, academic_year_id, section_id, subject_id)
    WHERE subject_id IS NOT NULL AND effective_to IS NULL;
CREATE INDEX teaching_assignment_staff_idx ON teaching_assignment (school_id, staff_id, academic_year_id);

-- Weekly timetable (FR 6.4, optional)
CREATE TABLE timetable_period (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT      NOT NULL REFERENCES school (id),
    academic_year_id  BIGINT      NOT NULL,
    section_id        BIGINT      NOT NULL,
    weekday           SMALLINT    NOT NULL CHECK (weekday BETWEEN 1 AND 7),
    period_no         SMALLINT    NOT NULL CHECK (period_no BETWEEN 1 AND 15),
    start_time        TIME,
    end_time          TIME,
    subject_id        BIGINT,
    staff_id          BIGINT,
    CONSTRAINT timetable_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id),
    CONSTRAINT timetable_section_fk FOREIGN KEY (school_id, section_id) REFERENCES section (school_id, id),
    CONSTRAINT timetable_subject_fk FOREIGN KEY (school_id, subject_id) REFERENCES subject (school_id, id),
    CONSTRAINT timetable_staff_fk FOREIGN KEY (school_id, staff_id) REFERENCES staff (school_id, id),
    CONSTRAINT timetable_slot_uq UNIQUE (school_id, academic_year_id, section_id, weekday, period_no)
);
CREATE INDEX timetable_staff_idx ON timetable_period (school_id, academic_year_id, staff_id);

-- ---------------------------------------------------------------------
-- Admissions (FR 4)
-- ---------------------------------------------------------------------
CREATE TABLE enquiry_status (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id      BIGINT      NOT NULL REFERENCES school (id),
    name           VARCHAR(60) NOT NULL,
    colour         VARCHAR(7)  NOT NULL DEFAULT '#64748b' CHECK (colour ~ '^#[0-9a-fA-F]{6}$'),
    display_order  INT         NOT NULL,
    system_code    VARCHAR(20) CHECK (system_code IN ('NEW', 'FOLLOW_UP', 'APPROVED', 'NOT_PROCEEDING', 'ENROLLED')),
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT enquiry_status_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT enquiry_status_code_uq UNIQUE (school_id, system_code)
);
CREATE UNIQUE INDEX enquiry_status_name_uq ON enquiry_status (school_id, lower(name));

CREATE TABLE enquiry (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT        NOT NULL REFERENCES school (id),
    registration_no   VARCHAR(20)   NOT NULL,
    enquiry_date      DATE          NOT NULL,
    source            VARCHAR(10)   NOT NULL CHECK (source IN ('WALK_IN', 'PHONE', 'ONLINE')),
    child_name        VARCHAR(200)  NOT NULL,
    gender            VARCHAR(10)   CHECK (gender IN ('MALE', 'FEMALE')),
    dob               DATE,
    class_applied_id  BIGINT,
    academic_year_id  BIGINT,
    parent_name       VARCHAR(200),
    parent_phone      VARCHAR(30),
    parent_cnic       VARCHAR(20),
    address           VARCHAR(500),
    previous_school   VARCHAR(200),
    remarks           VARCHAR(1000),
    status_id         BIGINT        NOT NULL,
    test_date         DATE,
    test_obtained     NUMERIC(6,2),
    test_total        NUMERIC(6,2),
    test_remark       VARCHAR(500),
    student_id        BIGINT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT enquiry_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT enquiry_registration_uq UNIQUE (school_id, registration_no),
    CONSTRAINT enquiry_class_fk FOREIGN KEY (school_id, class_applied_id) REFERENCES school_class (school_id, id),
    CONSTRAINT enquiry_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id),
    CONSTRAINT enquiry_status_fk FOREIGN KEY (school_id, status_id) REFERENCES enquiry_status (school_id, id),
    CONSTRAINT enquiry_test_marks CHECK (test_obtained IS NULL OR test_total IS NULL OR (test_obtained >= 0 AND test_obtained <= test_total))
);
CREATE INDEX enquiry_status_idx ON enquiry (school_id, status_id, enquiry_date DESC);
CREATE INDEX enquiry_phone_idx ON enquiry (school_id, parent_phone);
CREATE INDEX enquiry_name_trgm ON enquiry USING gin (lower(child_name) gin_trgm_ops);

CREATE TABLE enquiry_remark (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id    BIGINT        NOT NULL REFERENCES school (id),
    enquiry_id   BIGINT        NOT NULL,
    text         VARCHAR(1000) NOT NULL,
    by_name      VARCHAR(200),
    at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT enquiry_remark_enquiry_fk FOREIGN KEY (school_id, enquiry_id) REFERENCES enquiry (school_id, id)
);

-- Terms & conditions and entry-test syllabus per class (FR 4.11)
CREATE TABLE admission_content (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id   BIGINT       NOT NULL REFERENCES school (id),
    kind        VARCHAR(10)  NOT NULL CHECK (kind IN ('TERMS', 'SYLLABUS')),
    class_id    BIGINT,
    body        TEXT         NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT admission_content_class_fk FOREIGN KEY (school_id, class_id) REFERENCES school_class (school_id, id)
);
CREATE UNIQUE INDEX admission_content_uq ON admission_content (school_id, kind, coalesce(class_id, 0));

-- ---------------------------------------------------------------------
-- Students (FR 4.7, 5)
-- ---------------------------------------------------------------------
CREATE TABLE student (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id        BIGINT        NOT NULL REFERENCES school (id),
    gr_no            VARCHAR(20)   NOT NULL,
    name             VARCHAR(200)  NOT NULL,
    gender           VARCHAR(10)   NOT NULL CHECK (gender IN ('MALE', 'FEMALE')),
    dob              DATE,
    place_of_birth   VARCHAR(100),
    b_form_no        VARCHAR(20),
    religion         VARCHAR(40),
    nationality      VARCHAR(40)   DEFAULT 'Pakistani',
    phone            VARCHAR(30),
    address          VARCHAR(500),
    admission_date   DATE          NOT NULL,
    first_class_id   BIGINT,
    previous_school  VARCHAR(200),
    study_group      VARCHAR(40),
    seat_no          VARCHAR(30),
    photo_file_id    BIGINT,
    family_id        BIGINT        NOT NULL,
    enquiry_id       BIGINT,
    status           VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LEFT')),
    left_date        DATE,
    notes            VARCHAR(1000),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT student_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT student_gr_uq UNIQUE (school_id, gr_no),
    CONSTRAINT student_family_fk FOREIGN KEY (school_id, family_id) REFERENCES family (school_id, id),
    CONSTRAINT student_first_class_fk FOREIGN KEY (school_id, first_class_id) REFERENCES school_class (school_id, id),
    CONSTRAINT student_photo_fk FOREIGN KEY (school_id, photo_file_id) REFERENCES stored_file (school_id, id),
    CONSTRAINT student_enquiry_fk FOREIGN KEY (school_id, enquiry_id) REFERENCES enquiry (school_id, id)
);
CREATE INDEX student_family_idx ON student (school_id, family_id);
CREATE INDEX student_name_trgm ON student USING gin (lower(name) gin_trgm_ops);
CREATE INDEX student_b_form_idx ON student (school_id, b_form_no);

ALTER TABLE enquiry ADD CONSTRAINT enquiry_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id);

-- Class membership over time (FR 5.4, 5.5, 9.6): past attendance/results keep the class the student was in.
CREATE TABLE enrolment (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT       NOT NULL REFERENCES school (id),
    student_id        BIGINT       NOT NULL,
    academic_year_id  BIGINT       NOT NULL,
    class_id          BIGINT       NOT NULL,
    section_id        BIGINT       NOT NULL,
    roll_no           VARCHAR(20),
    effective_from    DATE         NOT NULL,
    effective_to      DATE,
    kind              VARCHAR(12)  NOT NULL CHECK (kind IN ('ADMISSION', 'MOVE', 'PROMOTION', 'REPEAT', 'MIGRATED')),
    reason            VARCHAR(300),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT enrolment_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT enrolment_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id),
    CONSTRAINT enrolment_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id),
    CONSTRAINT enrolment_class_fk FOREIGN KEY (school_id, class_id) REFERENCES school_class (school_id, id),
    CONSTRAINT enrolment_section_fk FOREIGN KEY (school_id, section_id) REFERENCES section (school_id, id),
    CONSTRAINT enrolment_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT enrolment_no_overlap EXCLUDE USING gist (
        school_id WITH =, student_id WITH =, daterange(effective_from, effective_to, '[]') WITH &&)
);
CREATE INDEX enrolment_section_idx ON enrolment (school_id, academic_year_id, section_id);
CREATE INDEX enrolment_student_idx ON enrolment (school_id, student_id, effective_from DESC);

-- Extra contacts / separate guardians with message routing (FR 8.3)
CREATE TABLE student_contact (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id             BIGINT       NOT NULL REFERENCES school (id),
    student_id            BIGINT       NOT NULL,
    name                  VARCHAR(200) NOT NULL,
    relationship          VARCHAR(60)  NOT NULL,
    phone                 VARCHAR(30),
    cnic                  VARCHAR(20),
    can_collect           BOOLEAN      NOT NULL DEFAULT FALSE,
    receives_attendance   BOOLEAN      NOT NULL DEFAULT FALSE,
    receives_fees         BOOLEAN      NOT NULL DEFAULT FALSE,
    receives_results      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT student_contact_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id)
);

CREATE TABLE student_document (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id   BIGINT       NOT NULL REFERENCES school (id),
    student_id  BIGINT       NOT NULL,
    doc_type    VARCHAR(40)  NOT NULL,
    title       VARCHAR(200) NOT NULL,
    file_id     BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT student_document_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id),
    CONSTRAINT student_document_file_fk FOREIGN KEY (school_id, file_id) REFERENCES stored_file (school_id, id)
);

-- Withdrawal / leaving (FR 5.6). Outstanding balance is filled in from Phase 3 (fees).
CREATE TABLE withdrawal (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id             BIGINT        NOT NULL REFERENCES school (id),
    student_id            BIGINT        NOT NULL,
    academic_year_id      BIGINT        NOT NULL,
    leaving_date          DATE          NOT NULL,
    reason                VARCHAR(500)  NOT NULL,
    outstanding_balance   NUMERIC(14,2),
    recorded_by_name      VARCHAR(200),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT withdrawal_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id),
    CONSTRAINT withdrawal_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id)
);

-- Houses, clubs and achievements (FR 5.10)
CREATE TABLE activity_group (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id   BIGINT       NOT NULL REFERENCES school (id),
    kind        VARCHAR(10)  NOT NULL CHECK (kind IN ('HOUSE', 'CLUB')),
    name        VARCHAR(100) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT activity_group_school_id_uq UNIQUE (school_id, id)
);
CREATE UNIQUE INDEX activity_group_name_uq ON activity_group (school_id, kind, lower(name));

CREATE TABLE student_activity (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id    BIGINT        NOT NULL REFERENCES school (id),
    student_id   BIGINT        NOT NULL,
    kind         VARCHAR(12)   NOT NULL CHECK (kind IN ('MEMBERSHIP', 'ACHIEVEMENT')),
    group_id     BIGINT,
    role         VARCHAR(60),
    title        VARCHAR(200),
    description  VARCHAR(1000),
    achieved_on  DATE,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT student_activity_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id),
    CONSTRAINT student_activity_group_fk FOREIGN KEY (school_id, group_id) REFERENCES activity_group (school_id, id)
);

-- Issued certificates (FR 5.8, 6.7): serial number + the data printed (snapshot), insert-only
CREATE TABLE certificate_issue (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT       NOT NULL REFERENCES school (id),
    cert_type         VARCHAR(20)  NOT NULL CHECK (cert_type IN ('LEAVING', 'DATE_OF_BIRTH', 'CHARACTER', 'APPRECIATION',
                                                              'PROVISIONAL', 'HOPE', 'EXPERIENCE')),
    serial_no         VARCHAR(30)  NOT NULL,
    student_id        BIGINT,
    staff_id          BIGINT,
    content           JSONB        NOT NULL,
    issued_by_name    VARCHAR(200),
    issued_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT certificate_serial_uq UNIQUE (school_id, cert_type, serial_no),
    CONSTRAINT certificate_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id),
    CONSTRAINT certificate_staff_fk FOREIGN KEY (school_id, staff_id) REFERENCES staff (school_id, id),
    CONSTRAINT certificate_subject CHECK ((student_id IS NULL) <> (staff_id IS NULL))
);

-- Year rollover record (FR 1.3)
CREATE TABLE year_rollover (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id        BIGINT       NOT NULL REFERENCES school (id),
    from_year_id     BIGINT       NOT NULL,
    to_year_id       BIGINT       NOT NULL,
    items            JSONB        NOT NULL,
    performed_by     VARCHAR(200),
    performed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT year_rollover_from_fk FOREIGN KEY (school_id, from_year_id) REFERENCES academic_year (school_id, id),
    CONSTRAINT year_rollover_to_fk FOREIGN KEY (school_id, to_year_id) REFERENCES academic_year (school_id, id)
);

-- ---------------------------------------------------------------------
-- Default enquiry statuses for existing schools (new schools get them at creation)
-- ---------------------------------------------------------------------
INSERT INTO enquiry_status (school_id, name, colour, display_order, system_code)
SELECT s.id, v.name, v.colour, v.ord, v.code
FROM school s
CROSS JOIN (VALUES ('New enquiry', '#2563eb', 1, 'NEW'),
                   ('Follow-up needed', '#d97706', 2, 'FOLLOW_UP'),
                   ('Admission approved', '#16a34a', 3, 'APPROVED'),
                   ('Not proceeding', '#6b7280', 4, 'NOT_PROCEEDING'),
                   ('Enrolled', '#7c3aed', 5, 'ENROLLED')) AS v(name, colour, ord, code);

-- ---------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['number_sequence', 'family', 'staff', 'staff_document', 'salary_profile',
                             'teaching_assignment', 'timetable_period', 'enquiry_status', 'enquiry', 'enquiry_remark',
                             'admission_content', 'student', 'enrolment', 'student_contact', 'student_document',
                             'withdrawal', 'activity_group', 'student_activity', 'certificate_issue', 'year_rollover']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I
                            USING (school_id = app_current_school() OR app_all_schools())
                            WITH CHECK (school_id = app_current_school() OR app_all_schools())', t);
    END LOOP;
END
$$;

-- ---------------------------------------------------------------------
-- Grants. History tables are insert-only for the app.
-- ---------------------------------------------------------------------
GRANT SELECT, INSERT, UPDATE ON number_sequence, family, staff, enquiry, student TO sms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON staff_document, teaching_assignment, timetable_period, enquiry_status,
    admission_content, student_contact, student_document, activity_group, student_activity TO sms_app;
-- DELETE only removes enrolments that never took effect (e.g. next-year promotion of a student who then left).
GRANT SELECT, INSERT, UPDATE, DELETE ON enrolment TO sms_app;
GRANT SELECT, INSERT ON salary_profile, enquiry_remark, withdrawal, certificate_issue, year_rollover TO sms_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO sms_app;
