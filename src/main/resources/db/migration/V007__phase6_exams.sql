-- =====================================================================
-- V007 Phase 6: examinations and results (FR 14). Published results are frozen snapshots (insert-only, versioned);
-- a correction after publishing is a new version with a reason (FR 14.11).
-- =====================================================================

-- Grade scales (D-14: percentage to 2 dp, band lower bound inclusive). GRADES scales are for grade-only classes.
CREATE TABLE grade_scale (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id   BIGINT       NOT NULL REFERENCES school (id),
    name        VARCHAR(60)  NOT NULL,
    kind        VARCHAR(10)  NOT NULL CHECK (kind IN ('MARKS', 'GRADES')),
    is_default  BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT grade_scale_school_id_uq UNIQUE (school_id, id)
);
CREATE UNIQUE INDEX grade_scale_default_uq ON grade_scale (school_id, kind) WHERE is_default;

CREATE TABLE grade_band (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id      BIGINT        NOT NULL REFERENCES school (id),
    scale_id       BIGINT        NOT NULL,
    grade          VARCHAR(10)   NOT NULL,
    min_percent    NUMERIC(5, 2) CHECK (min_percent IS NULL OR (min_percent >= 0 AND min_percent <= 100)),
    remark         VARCHAR(100),
    passing        BOOLEAN       NOT NULL DEFAULT true,
    display_order  INT           NOT NULL,
    CONSTRAINT grade_band_scale_fk FOREIGN KEY (school_id, scale_id) REFERENCES grade_scale (school_id, id),
    CONSTRAINT grade_band_uq UNIQUE (school_id, scale_id, grade)
);

CREATE TABLE exam (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id             BIGINT        NOT NULL REFERENCES school (id),
    academic_year_id      BIGINT        NOT NULL,
    name                  VARCHAR(100)  NOT NULL,
    start_date            DATE,
    end_date              DATE,
    comments              VARCHAR(1000),
    counts_in_cumulative  BOOLEAN       NOT NULL DEFAULT true,
    weight                NUMERIC(5, 2) NOT NULL DEFAULT 1 CHECK (weight > 0),
    visible               BOOLEAN       NOT NULL DEFAULT false,
    created_by            VARCHAR(200),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version               BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT exam_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT exam_dates CHECK (end_date IS NULL OR start_date IS NULL OR end_date >= start_date),
    CONSTRAINT exam_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id)
);
CREATE UNIQUE INDEX exam_name_uq ON exam (school_id, academic_year_id, lower(name));

-- Each class in an exam moves Draft → Reviewed → Published on its own (FR 14.10).
CREATE TABLE exam_class (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id          BIGINT        NOT NULL REFERENCES school (id),
    exam_id            BIGINT        NOT NULL,
    class_id           BIGINT        NOT NULL,
    grade_scale_id     BIGINT        NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'REVIEWED', 'PUBLISHED')),
    reviewed_by        VARCHAR(200),
    reviewed_at        TIMESTAMPTZ,
    published_by       VARCHAR(200),
    published_at       TIMESTAMPTZ,
    published_version  INT           NOT NULL DEFAULT 0,
    unlocked_by        VARCHAR(200),
    unlocked_at        TIMESTAMPTZ,
    unlock_reason      VARCHAR(500),
    CONSTRAINT exam_class_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT exam_class_uq UNIQUE (school_id, exam_id, class_id),
    CONSTRAINT exam_class_exam_fk FOREIGN KEY (school_id, exam_id) REFERENCES exam (school_id, id),
    CONSTRAINT exam_class_class_fk FOREIGN KEY (school_id, class_id) REFERENCES school_class (school_id, id),
    CONSTRAINT exam_class_scale_fk FOREIGN KEY (school_id, grade_scale_id) REFERENCES grade_scale (school_id, id)
);

-- Papers of a class in an exam, with the datesheet (FR 14.2, 14.3).
CREATE TABLE exam_subject (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id      BIGINT        NOT NULL REFERENCES school (id),
    exam_class_id  BIGINT        NOT NULL,
    subject_id     BIGINT        NOT NULL,
    max_marks      NUMERIC(6, 2) NOT NULL CHECK (max_marks > 0),
    passing_marks  NUMERIC(6, 2) CHECK (passing_marks IS NULL OR passing_marks >= 0),
    display_order  INT           NOT NULL,
    paper_date     DATE,
    start_time     TIME,
    end_time       TIME,
    syllabus       VARCHAR(1000),
    CONSTRAINT exam_subject_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT exam_subject_uq UNIQUE (school_id, exam_class_id, subject_id),
    CONSTRAINT exam_subject_passing CHECK (passing_marks IS NULL OR passing_marks <= max_marks),
    CONSTRAINT exam_subject_class_fk FOREIGN KEY (school_id, exam_class_id) REFERENCES exam_class (school_id, id),
    CONSTRAINT exam_subject_subject_fk FOREIGN KEY (school_id, subject_id) REFERENCES subject (school_id, id)
);

-- A mark: obtained marks, or a grade (grade-only classes), or a flag. Nothing at all = missing (FR 14.6).
CREATE TABLE mark (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id        BIGINT        NOT NULL REFERENCES school (id),
    exam_subject_id  BIGINT        NOT NULL,
    student_id       BIGINT        NOT NULL,
    obtained         NUMERIC(6, 2) CHECK (obtained IS NULL OR obtained >= 0),
    grade            VARCHAR(10),
    flag             VARCHAR(10)   CHECK (flag IN ('ABSENT', 'EXEMPT')),
    entered_by       VARCHAR(200),
    entered_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT mark_once UNIQUE (school_id, exam_subject_id, student_id),
    CONSTRAINT mark_one_value CHECK ((flag IS NOT NULL AND obtained IS NULL AND grade IS NULL)
                                  OR (flag IS NULL AND (obtained IS NOT NULL OR grade IS NOT NULL))),
    CONSTRAINT mark_subject_fk FOREIGN KEY (school_id, exam_subject_id) REFERENCES exam_subject (school_id, id),
    CONSTRAINT mark_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id)
);

-- Every change to a mark after the class was first published (FR 14.11). Insert-only.
CREATE TABLE mark_change (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id        BIGINT        NOT NULL REFERENCES school (id),
    exam_subject_id  BIGINT        NOT NULL,
    student_id       BIGINT        NOT NULL,
    old_value        VARCHAR(20),
    new_value        VARCHAR(20),
    reason           VARCHAR(500)  NOT NULL,
    changed_by       VARCHAR(200),
    changed_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE result_remark (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id          BIGINT        NOT NULL REFERENCES school (id),
    exam_class_id      BIGINT        NOT NULL,
    student_id         BIGINT        NOT NULL,
    teacher_remark     VARCHAR(500),
    principal_remark   VARCHAR(500),
    updated_by         VARCHAR(200),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT result_remark_uq UNIQUE (school_id, exam_class_id, student_id),
    CONSTRAINT result_remark_class_fk FOREIGN KEY (school_id, exam_class_id) REFERENCES exam_class (school_id, id)
);

-- Frozen at publish; each correction adds a version (FR 14.11, 14.15). Insert-only.
CREATE TABLE result_snapshot (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id       BIGINT         NOT NULL REFERENCES school (id),
    exam_class_id   BIGINT         NOT NULL,
    student_id      BIGINT         NOT NULL,
    version         INT            NOT NULL,
    section_id      BIGINT,
    total_obtained  NUMERIC(8, 2),
    total_max       NUMERIC(8, 2),
    percent         NUMERIC(5, 2),
    grade           VARCHAR(10),
    position        INT,
    passed          BOOLEAN,
    data            JSONB          NOT NULL,
    published_by    VARCHAR(200),
    published_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT result_snapshot_uq UNIQUE (school_id, exam_class_id, student_id, version),
    CONSTRAINT result_snapshot_class_fk FOREIGN KEY (school_id, exam_class_id) REFERENCES exam_class (school_id, id),
    CONSTRAINT result_snapshot_student_fk FOREIGN KEY (school_id, student_id) REFERENCES student (school_id, id)
);

-- Default scales for existing schools (new schools get them at creation)
INSERT INTO grade_scale (school_id, name, kind, is_default)
SELECT id, 'Standard grades', 'MARKS', true FROM school
UNION ALL SELECT id, 'Early years', 'GRADES', true FROM school;

INSERT INTO grade_band (school_id, scale_id, grade, min_percent, remark, passing, display_order)
SELECT g.school_id, g.id, v.grade, v.min_pct, v.remark, v.passing, v.ord
FROM grade_scale g
CROSS JOIN (VALUES ('A++', 95, 'Outstanding', true, 1), ('A+', 90, 'Excellent', true, 2), ('A', 80, 'Very good', true, 3),
                   ('B', 70, 'Good', true, 4), ('C', 60, 'Fair', true, 5), ('D', 50, 'Satisfactory', true, 6),
                   ('E', 40, 'Pass', true, 7), ('F', 0, 'Fail', false, 8)) AS v(grade, min_pct, remark, passing, ord)
WHERE g.kind = 'MARKS';

INSERT INTO grade_band (school_id, scale_id, grade, min_percent, remark, passing, display_order)
SELECT g.school_id, g.id, v.grade, NULL, v.remark, true, v.ord
FROM grade_scale g
CROSS JOIN (VALUES ('A', 'Excellent', 1), ('B', 'Very good', 2), ('C', 'Good', 3), ('D', 'Satisfactory', 4),
                   ('E', 'Needs improvement', 5)) AS v(grade, remark, ord)
WHERE g.kind = 'GRADES';

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['grade_scale', 'grade_band', 'exam', 'exam_class', 'exam_subject', 'mark', 'mark_change',
                             'result_remark', 'result_snapshot']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I
                            USING (school_id = app_current_school() OR app_all_schools())
                            WITH CHECK (school_id = app_current_school() OR app_all_schools())', t);
    END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE ON grade_scale, exam, exam_class, result_remark TO sms_app;
-- Clearing a mark (back to missing) deletes it; changes after publishing are logged in mark_change.
GRANT SELECT, INSERT, UPDATE, DELETE ON mark TO sms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON grade_band, exam_subject TO sms_app;
GRANT SELECT, INSERT ON mark_change, result_snapshot TO sms_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO sms_app;
