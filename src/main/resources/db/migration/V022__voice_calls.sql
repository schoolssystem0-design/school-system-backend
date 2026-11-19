-- Urdu voice alerts from the school phone (same SIM as SMS): outbox channel and family preference.

ALTER TABLE message_outbox DROP CONSTRAINT IF EXISTS message_outbox_channel_check;
ALTER TABLE message_outbox ADD CONSTRAINT message_outbox_channel_check
    CHECK (channel IN ('SMS', 'EMAIL', 'VOICE'));

ALTER TABLE family DROP CONSTRAINT IF EXISTS family_preferred_channel_check;
ALTER TABLE family ADD CONSTRAINT family_preferred_channel_check
    CHECK (preferred_channel IN ('SMS', 'WHATSAPP', 'VOICE', 'NONE'));