-- Why the last report from a school's phone was refused (wrong signing key, phone clock wrong, ...), so the SMS phone
-- page can say what to fix instead of the phone silently looking "offline".
ALTER TABLE sms_gateway ADD COLUMN last_refused_at     TIMESTAMPTZ;
ALTER TABLE sms_gateway ADD COLUMN last_refused_reason VARCHAR(300);
