-- Automatic timetable: how many periods a week each subject has in a class, the school's bell schedule, and periods
-- the user has locked so the generator keeps them.

ALTER TABLE class_subject
    ADD COLUMN periods_per_week SMALLINT CHECK (periods_per_week BETWEEN 0 AND 60),
    ADD COLUMN double_period    BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE timetable_period
    ADD COLUMN locked BOOLEAN NOT NULL DEFAULT false;

-- The school day in order: teaching periods and breaks. Teaching periods are numbered 1, 2, 3... in order (the
-- timetable's period_no); breaks sit between them. on_half_day: the slot also happens on half days.
CREATE TABLE bell_slot (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT       NOT NULL REFERENCES school (id),
    academic_year_id  BIGINT       NOT NULL,
    seq               SMALLINT     NOT NULL CHECK (seq BETWEEN 1 AND 30),
    kind              VARCHAR(6)   NOT NULL CHECK (kind IN ('PERIOD', 'BREAK')),
    label             VARCHAR(40),
    start_time        TIME         NOT NULL,
    end_time          TIME         NOT NULL,
    on_half_day       BOOLEAN      NOT NULL DEFAULT true,
    CONSTRAINT bell_slot_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id),
    CONSTRAINT bell_slot_seq_uq UNIQUE (school_id, academic_year_id, seq),
    CONSTRAINT bell_slot_times CHECK (end_time > start_time)
);

ALTER TABLE bell_slot ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bell_slot
    USING (school_id = app_current_school() OR app_all_schools())
    WITH CHECK (school_id = app_current_school() OR app_all_schools());

GRANT SELECT, INSERT, UPDATE, DELETE ON bell_slot TO sms_app;
