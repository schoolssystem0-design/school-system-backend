-- SMS through a school's own Android phone running SMS Gate (docs: School SMS Notification System guide, v2).
-- The phone is one more way out of the existing message outbox, so every message type can use it.

-- ---------------------------------------------------------------------
-- Outbox: delivery states reported by the phone, priority, quiet-hour scheduling and expiry
-- ---------------------------------------------------------------------
ALTER TABLE message_outbox DROP CONSTRAINT IF EXISTS message_outbox_status_check;
ALTER TABLE message_outbox ALTER COLUMN status TYPE VARCHAR(12);
ALTER TABLE message_outbox ADD CONSTRAINT message_outbox_status_check
    CHECK (status IN ('PENDING', 'QUEUED', 'SENT', 'DELIVERED', 'FAILED', 'CANCELLED'));

-- 1 = most urgent (absence alerts), 5 = normal, 9 = can wait (birthdays). The dispatcher sends lower numbers first.
ALTER TABLE message_outbox ADD COLUMN priority      SMALLINT    NOT NULL DEFAULT 5;
-- After this time the message is pointless (an absence alert after the day is over) and is cancelled, not sent.
ALTER TABLE message_outbox ADD COLUMN expires_at    TIMESTAMPTZ;
-- Set when the message was handed to the school's phone (SMS Gate). Our own id is sent with it, so a retried hand-over
-- never sends the SMS twice and webhooks always find the row.
ALTER TABLE message_outbox ADD COLUMN gateway_id    VARCHAR(64);
ALTER TABLE message_outbox ADD COLUMN queued_at     TIMESTAMPTZ;
ALTER TABLE message_outbox ADD COLUMN delivered_at  TIMESTAMPTZ;
ALTER TABLE message_outbox ADD COLUMN status_checked_at TIMESTAMPTZ;

DROP INDEX IF EXISTS message_outbox_pending_idx;
CREATE INDEX message_outbox_pending_idx ON message_outbox (priority, next_attempt_at, id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX message_outbox_gateway_uq ON message_outbox (gateway_id) WHERE gateway_id IS NOT NULL;
CREATE INDEX message_outbox_queued_idx ON message_outbox (queued_at) WHERE status = 'QUEUED';

-- ---------------------------------------------------------------------
-- One SMS Gate connection per school (its own phone and SIM)
-- ---------------------------------------------------------------------
CREATE TABLE sms_gateway (
    school_id           BIGINT        PRIMARY KEY REFERENCES school (id),
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    base_url            VARCHAR(300)  NOT NULL,
    username            VARCHAR(100)  NOT NULL,
    -- AES-GCM encrypted with APP_SECRETS_KEY; never returned by the API
    password_enc        VARCHAR(500)  NOT NULL,
    signing_key_enc     VARCHAR(500)  NOT NULL,
    -- Random value in the webhook address: identifies the school without a sign-in
    webhook_token       VARCHAR(64)   NOT NULL,
    webhooks_registered_at TIMESTAMPTZ,
    alert_email         VARCHAR(254),
    sim_number          SMALLINT      CHECK (sim_number BETWEEN 1 AND 3),
    -- Phone health (any webhook counts as "seen"; system:ping carries battery and signal)
    last_seen_at        TIMESTAMPTZ,
    battery_level       SMALLINT,
    charging            BOOLEAN,
    health              JSONB,
    offline_alerted_at  TIMESTAMPTZ,
    connected_by        VARCHAR(200),
    connected_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT sms_gateway_token_uq UNIQUE (webhook_token)
);

-- Webhook events already handled (the phone repeats an event until it gets a 2xx; its id stays the same).
CREATE TABLE sms_gateway_event (
    school_id    BIGINT       NOT NULL REFERENCES school (id),
    event_id     VARCHAR(100) NOT NULL,
    event        VARCHAR(40)  NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (school_id, event_id)
);
CREATE INDEX sms_gateway_event_received_idx ON sms_gateway_event (received_at);

-- ---------------------------------------------------------------------
-- Message language per family (English or Roman Urdu) and template wording per language
-- ---------------------------------------------------------------------
ALTER TABLE family ADD COLUMN message_language VARCHAR(5) NOT NULL DEFAULT 'EN'
    CHECK (message_language IN ('EN', 'UR'));

ALTER TABLE message_template ADD COLUMN language VARCHAR(5) NOT NULL DEFAULT 'EN' CHECK (language IN ('EN', 'UR'));
ALTER TABLE message_template DROP CONSTRAINT message_template_uq;
ALTER TABLE message_template ADD CONSTRAINT message_template_uq UNIQUE (school_id, code, language);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['sms_gateway', 'sms_gateway_event']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I
                            USING (school_id = app_current_school() OR app_all_schools())
                            WITH CHECK (school_id = app_current_school() OR app_all_schools())', t);
    END LOOP;
END
$$;

GRANT SELECT, INSERT, UPDATE, DELETE ON sms_gateway TO sms_app;
GRANT SELECT, INSERT, DELETE ON sms_gateway_event TO sms_app;
