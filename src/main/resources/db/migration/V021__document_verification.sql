-- Public QR verification for printed school documents (certificates, report cards, fee receipts).
-- Rows are looked up by unguessable token only (no school session needed).

CREATE TABLE document_verification (
    token          VARCHAR(32)  PRIMARY KEY,
    school_id      BIGINT       NOT NULL REFERENCES school (id) ON DELETE CASCADE,
    doc_kind       VARCHAR(30)  NOT NULL,
  -- reference type/id tie back to the source row when useful
    ref_type       VARCHAR(40)  NOT NULL,
    ref_id         BIGINT       NOT NULL,
    document_title VARCHAR(200) NOT NULL,
    subject_line   VARCHAR(300) NOT NULL,
    issued_on      DATE         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX document_verification_school_idx ON document_verification (school_id, created_at DESC);

GRANT SELECT, INSERT ON document_verification TO sms_app;