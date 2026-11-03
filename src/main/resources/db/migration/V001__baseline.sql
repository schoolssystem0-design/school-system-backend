-- =====================================================================
-- V001 baseline: platform tables, first tenant tables, row-level security
-- See docs/ARCHITECTURE.md §4.0 and docs/DATA-MODEL.md
-- =====================================================================

-- ---------------------------------------------------------------------
-- Runtime role. The application connects as a LOGIN role that is a member
-- of sms_app (created by AppDatabaseRoleCallback after migrate). It does
-- not own any table, so row-level security always applies to it.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'sms_app') THEN
        CREATE ROLE sms_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO sms_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO sms_app;

-- Tenant helpers used by every RLS policy.
-- app.school_id   : set on every connection checkout from the current TenantContext
-- app.all_schools : 'on' only inside super admin cross-school endpoints
CREATE FUNCTION app_current_school() RETURNS BIGINT
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.school_id', true), '')::BIGINT $$;

CREATE FUNCTION app_all_schools() RETURNS BOOLEAN
    LANGUAGE sql STABLE
AS $$ SELECT COALESCE(current_setting('app.all_schools', true), '') = 'on' $$;

-- =====================================================================
-- Platform tables (not tenant-scoped, no RLS)
-- =====================================================================
CREATE TABLE school (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'SUSPENDED', 'OFFBOARDING')),
    timezone    VARCHAR(64)  NOT NULL DEFAULT 'Asia/Karachi',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT school_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9-]{1,19}$')
);
CREATE UNIQUE INDEX school_code_uq ON school (code);

CREATE TABLE school_domain (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id   BIGINT       NOT NULL REFERENCES school (id),
    host        VARCHAR(253) NOT NULL,
    verified    BOOLEAN      NOT NULL DEFAULT FALSE,
    is_primary  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX school_domain_host_uq ON school_domain (lower(host));

CREATE TABLE platform_user (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email          VARCHAR(254) NOT NULL,
    name           VARCHAR(200) NOT NULL,
    password_hash  VARCHAR(200) NOT NULL,
    totp_secret    VARCHAR(200),
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX platform_user_email_uq ON platform_user (lower(email));

CREATE TABLE platform_audit_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id    BIGINT       REFERENCES platform_user (id),
    action      VARCHAR(60)  NOT NULL,
    school_id   BIGINT       REFERENCES school (id),
    details     JSONB,
    ip          VARCHAR(64),
    at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX platform_audit_log_at_idx ON platform_audit_log (at DESC);

-- =====================================================================
-- Tenant tables: school_id NOT NULL, RLS, school_id in unique + foreign keys
-- =====================================================================
CREATE TABLE app_user (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id             BIGINT       NOT NULL REFERENCES school (id),
    username              VARCHAR(60)  NOT NULL,
    display_name          VARCHAR(200) NOT NULL,
    password_hash         VARCHAR(200) NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                          CHECK (status IN ('ACTIVE', 'INACTIVE', 'LOCKED')),
    must_change_password  BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_attempts       INT          NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT app_user_school_id_uq UNIQUE (school_id, id)
);
CREATE UNIQUE INDEX app_user_username_uq ON app_user (school_id, lower(username));

CREATE TABLE login_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id   BIGINT       NOT NULL REFERENCES school (id),
    user_id     BIGINT,
    username    VARCHAR(60)  NOT NULL,
    result      VARCHAR(20)  NOT NULL CHECK (result IN ('SUCCESS', 'INVALID', 'BLOCKED')),
    ip          VARCHAR(64),
    user_agent  VARCHAR(400),
    at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT login_event_user_fk FOREIGN KEY (school_id, user_id) REFERENCES app_user (school_id, id)
);
CREATE INDEX login_event_school_at_idx ON login_event (school_id, at DESC);

CREATE TABLE audit_log (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    school_id    BIGINT       NOT NULL REFERENCES school (id),
    actor_type   VARCHAR(20)  NOT NULL CHECK (actor_type IN ('USER', 'PLATFORM', 'SYSTEM')),
    actor_id     BIGINT,
    action       VARCHAR(60)  NOT NULL,
    entity_type  VARCHAR(60),
    entity_id    BIGINT,
    before_data  JSONB,
    after_data   JSONB,
    reason       VARCHAR(500),
    ip           VARCHAR(64),
    at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_school_at_idx ON audit_log (school_id, at DESC);
CREATE INDEX audit_log_entity_idx ON audit_log (school_id, entity_type, entity_id);

-- RLS policies (same predicate on every tenant table)
ALTER TABLE app_user    ENABLE ROW LEVEL SECURITY;
ALTER TABLE login_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log   ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON app_user
    USING (school_id = app_current_school() OR app_all_schools())
    WITH CHECK (school_id = app_current_school() OR app_all_schools());
CREATE POLICY tenant_isolation ON login_event
    USING (school_id = app_current_school() OR app_all_schools())
    WITH CHECK (school_id = app_current_school() OR app_all_schools());
CREATE POLICY tenant_isolation ON audit_log
    USING (school_id = app_current_school() OR app_all_schools())
    WITH CHECK (school_id = app_current_school() OR app_all_schools());

-- =====================================================================
-- Spring Session JDBC (schema-postgresql.sql from spring-session-jdbc 4.1)
-- =====================================================================
CREATE TABLE spring_session (
    primary_id             CHAR(36)     NOT NULL,
    session_id             CHAR(36)     NOT NULL,
    creation_time          BIGINT       NOT NULL,
    last_access_time       BIGINT       NOT NULL,
    max_inactive_interval  INT          NOT NULL,
    expiry_time            BIGINT       NOT NULL,
    principal_name         VARCHAR(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id  CHAR(36)     NOT NULL,
    attribute_name      VARCHAR(200) NOT NULL,
    attribute_bytes     BYTEA        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

-- =====================================================================
-- Grants. Audit and login history are insert-only for the app.
-- =====================================================================
GRANT SELECT, INSERT, UPDATE ON school, school_domain, platform_user TO sms_app;
GRANT SELECT, INSERT          ON platform_audit_log                 TO sms_app;
GRANT SELECT, INSERT, UPDATE ON app_user                            TO sms_app;
GRANT SELECT, INSERT          ON login_event, audit_log             TO sms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON spring_session, spring_session_attributes TO sms_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO sms_app;
