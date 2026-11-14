-- Admissions made offline are sent later with an Idempotency-Key: sending the same admission again (a retry after a
-- dropped connection) returns the student admitted the first time instead of admitting the child twice.
ALTER TABLE student ADD COLUMN idempotency_key VARCHAR(100);
CREATE UNIQUE INDEX student_idempotency_uq ON student (school_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
