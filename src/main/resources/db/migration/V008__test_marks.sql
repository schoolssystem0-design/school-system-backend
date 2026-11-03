-- Simple test marks (DECISIONS D-51): a test is one subject of one section on one day. No exam set-up or publishing.

CREATE TABLE class_test (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT        NOT NULL REFERENCES school (id),
    academic_year_id  BIGINT        NOT NULL,
    section_id        BIGINT        NOT NULL,
    subject_id        BIGINT        NOT NULL,
    name              VARCHAR(100)  NOT NULL,
    test_date         DATE          NOT NULL,
    max_marks         NUMERIC(6, 2) NOT NULL CHECK (max_marks > 0),
    created_by        VARCHAR(200),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT class_test_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT class_test_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id),
    CONSTRAINT class_test_section_fk FOREIGN KEY (school_id, section_id) REFERENCES section (school_id, id),
    CONSTRAINT class_test_subject_fk FOREIGN KEY (school_id, subject_id) REFERENCES subject (school_id, id)
);

CREATE UNIQUE INDEX class_test_name_uq ON class_test (school_id, section_id, subject_id, test_date, lower(name));
CREATE INDEX class_test_section_idx ON class_test (school_id, section_id, test_date DESC);

-- No row = not entered yet.
CREATE TABLE test_mark (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id      BIGINT        NOT NULL REFERENCES school (id),
    class_test_id  BIGINT        NOT NULL,
    student_id     BIGINT        NOT NULL,
    obtained       NUMERIC(6, 2) CHECK (obtained IS NULL OR obtained >= 0),
    absent         BOOLEAN       NOT NULL DEFAULT FALSE,
    entered_by     VARCHAR(200),
    entered_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT test_mark_once UNIQUE (school_id, class_test_id, student_id),
    CONSTRAINT test_mark_one_value CHECK ((absent AND obtained IS NULL) OR (NOT absent AND obtained IS NOT NULL)),
    CONSTRAINT test_mark_test_fk FOREIGN KEY (school_id, class_test_id) REFERENCES class_test (school_id, id),
    CONSTRAINT test_mark_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id)
);

CREATE INDEX test_mark_student_idx ON test_mark (school_id, student_id);

-- Insert-only history of saved marks that were changed.
CREATE TABLE test_mark_change (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id      BIGINT        NOT NULL REFERENCES school (id),
    class_test_id  BIGINT        NOT NULL,
    student_id     BIGINT        NOT NULL,
    old_value      VARCHAR(20),
    new_value      VARCHAR(20),
    reason         VARCHAR(500)  NOT NULL,
    changed_by     VARCHAR(200),
    changed_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT test_mark_change_test_fk FOREIGN KEY (school_id, class_test_id) REFERENCES class_test (school_id, id),
    CONSTRAINT test_mark_change_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id)
);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['class_test', 'test_mark', 'test_mark_change']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I
                            USING (school_id = app_current_school() OR app_all_schools())
                            WITH CHECK (school_id = app_current_school() OR app_all_schools())', t);
    END LOOP;
END
$$;

-- A test with no marks yet can be deleted; a saved mark can be cleared only with a logged reason.
-- UPDATE is needed for the row lock (SELECT ... FOR UPDATE) while saving marks; the app never changes a test.
GRANT SELECT, INSERT, UPDATE, DELETE ON class_test TO sms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON test_mark TO sms_app;
GRANT SELECT, INSERT ON test_mark_change TO sms_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO sms_app;
