-- Phase 7: office tools (FR 3), message templates (FR 8.6), dashboard favourites (FR 3.12).

CREATE TABLE task (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id        BIGINT        NOT NULL REFERENCES school (id),
    description      VARCHAR(500)  NOT NULL,
    deadline         DATE,
    priority         VARCHAR(10)   NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL', 'URGENT')),
    status           VARCHAR(10)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DONE')),
    created_by_user  BIGINT,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_by     VARCHAR(200),
    completed_at     TIMESTAMPTZ,
    CONSTRAINT task_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT task_creator_fk FOREIGN KEY (school_id, created_by_user) REFERENCES app_user (school_id, id)
);
CREATE INDEX task_open_idx ON task (school_id, status, deadline);

CREATE TABLE task_share (
    school_id  BIGINT NOT NULL REFERENCES school (id),
    task_id    BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    PRIMARY KEY (school_id, task_id, user_id),
    CONSTRAINT task_share_task_fk FOREIGN KEY (school_id, task_id) REFERENCES task (school_id, id),
    CONSTRAINT task_share_user_fk FOREIGN KEY (school_id, user_id) REFERENCES app_user (school_id, id)
);
CREATE INDEX task_share_user_idx ON task_share (school_id, user_id);

CREATE TABLE complaint (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id         BIGINT        NOT NULL REFERENCES school (id),
    academic_year_id  BIGINT        NOT NULL,
    family_id         BIGINT,
    complainant       VARCHAR(200)  NOT NULL,
    phone             VARCHAR(30),
    subject           VARCHAR(200)  NOT NULL,
    details           VARCHAR(2000),
    status            VARCHAR(10)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    assigned_to_user  BIGINT,
    opened_by         VARCHAR(200),
    opened_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    closed_by         VARCHAR(200),
    closed_at         TIMESTAMPTZ,
    CONSTRAINT complaint_school_id_uq UNIQUE (school_id, id),
    CONSTRAINT complaint_year_fk FOREIGN KEY (school_id, academic_year_id) REFERENCES academic_year (school_id, id),
    CONSTRAINT complaint_family_fk FOREIGN KEY (school_id, family_id) REFERENCES family (school_id, id),
    CONSTRAINT complaint_assignee_fk FOREIGN KEY (school_id, assigned_to_user) REFERENCES app_user (school_id, id)
);
CREATE INDEX complaint_year_idx ON complaint (school_id, academic_year_id, status);

-- Insert-only history of each complaint.
CREATE TABLE complaint_remark (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id     BIGINT        NOT NULL REFERENCES school (id),
    complaint_id  BIGINT        NOT NULL,
    text          VARCHAR(1000) NOT NULL,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT complaint_remark_fk FOREIGN KEY (school_id, complaint_id) REFERENCES complaint (school_id, id)
);

CREATE TABLE circular (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id      BIGINT        NOT NULL REFERENCES school (id),
    title          VARCHAR(200)  NOT NULL,
    body           VARCHAR(5000) NOT NULL,
    circular_date  DATE          NOT NULL,
    created_by     VARCHAR(200),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX circular_date_idx ON circular (school_id, circular_date DESC, id DESC);

CREATE TABLE school_event (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id        BIGINT        NOT NULL REFERENCES school (id),
    title            VARCHAR(200)  NOT NULL,
    date_from        DATE          NOT NULL,
    date_to          DATE          NOT NULL,
    event_type       VARCHAR(10)   NOT NULL DEFAULT 'EVENT' CHECK (event_type IN ('EVENT', 'HOLIDAY', 'EXAM', 'OTHER')),
    details          VARCHAR(1000),
    show_on_website  BOOLEAN       NOT NULL DEFAULT FALSE,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT school_event_dates CHECK (date_to >= date_from)
);
CREATE INDEX school_event_date_idx ON school_event (school_id, date_from);

CREATE TABLE visitor_entry (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id   BIGINT        NOT NULL REFERENCES school (id),
    visit_date  DATE          NOT NULL,
    name        VARCHAR(200)  NOT NULL,
    purpose     VARCHAR(300),
    time_in     TIME,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX visitor_entry_date_idx ON visitor_entry (school_id, visit_date);

-- Only customised templates are stored; otherwise the built-in wording is used.
CREATE TABLE message_template (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id   BIGINT        NOT NULL REFERENCES school (id),
    code        VARCHAR(30)   NOT NULL,
    body        VARCHAR(500)  NOT NULL,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT message_template_uq UNIQUE (school_id, code)
);

CREATE TABLE user_favourite (
    school_id      BIGINT       NOT NULL REFERENCES school (id),
    user_id        BIGINT       NOT NULL,
    href           VARCHAR(200) NOT NULL,
    label          VARCHAR(100) NOT NULL,
    display_order  INT          NOT NULL,
    PRIMARY KEY (school_id, user_id, href),
    CONSTRAINT user_favourite_user_fk FOREIGN KEY (school_id, user_id) REFERENCES app_user (school_id, id)
);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['task', 'task_share', 'complaint', 'complaint_remark', 'circular', 'school_event',
                             'visitor_entry', 'message_template', 'user_favourite']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I
                            USING (school_id = app_current_school() OR app_all_schools())
                            WITH CHECK (school_id = app_current_school() OR app_all_schools())', t);
    END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE ON task, complaint TO sms_app;
GRANT SELECT, INSERT, DELETE ON task_share, user_favourite TO sms_app;
GRANT SELECT, INSERT ON complaint_remark, visitor_entry TO sms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON circular, school_event, message_template TO sms_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO sms_app;
