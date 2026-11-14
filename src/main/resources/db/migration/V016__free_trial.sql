-- Free trial (14 days): a school can sign itself up from the public site; one trial per internet address and per email.

-- When set, the school is on a free trial that ends at this moment. NULL = full (paid) access.
ALTER TABLE school ADD COLUMN trial_ends_at TIMESTAMPTZ;

-- Every trial ever started. Kept when the school is deleted (the address and email stay used).
-- The school column is deliberately not called school_id: removing a school must not remove this record.
CREATE TABLE trial_signup (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ip                 VARCHAR(64)  NOT NULL,
    email              VARCHAR(254) NOT NULL,
    school_code        VARCHAR(20)  NOT NULL,
    created_school_id  BIGINT       REFERENCES school (id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX trial_signup_ip_uq ON trial_signup (ip);
CREATE UNIQUE INDEX trial_signup_email_uq ON trial_signup (lower(email));

-- Delete only to release a reservation when creating the school failed in the same request.
GRANT SELECT, INSERT, UPDATE, DELETE ON trial_signup TO sms_app;
