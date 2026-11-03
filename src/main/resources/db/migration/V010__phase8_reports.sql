-- Phase 8: when a test mark was first entered, so a later correction does not make it count as entered late (FR 15.3).
ALTER TABLE test_mark ADD COLUMN first_entered_at TIMESTAMPTZ;
UPDATE test_mark SET first_entered_at = entered_at;
ALTER TABLE test_mark ALTER COLUMN first_entered_at SET NOT NULL;
ALTER TABLE test_mark ALTER COLUMN first_entered_at SET DEFAULT now();
