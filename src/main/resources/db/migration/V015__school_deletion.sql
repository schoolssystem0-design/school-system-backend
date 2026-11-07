-- Permanent removal of a school by the super admin (P-4 offboarding).
--
-- The application role cannot delete records (history is kept: payments, audit, students...), so the removal runs
-- in this function with the owner's rights (SECURITY DEFINER). Only the application may call it, and the API allows
-- it only to the super admin after the school code is typed again.
--
-- Everything in one transaction: rows are deleted table by table; a delete still blocked by rows that point at it
-- is undone and tried again after its dependants are gone. The platform audit history is kept (unlinked).

-- Approved salary sheets are locked, except while their own school is being removed.
CREATE OR REPLACE FUNCTION salary_line_locked() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('app.deleting_school', true) = OLD.school_id::text THEN
        RETURN OLD;
    END IF;
    IF (SELECT status FROM salary_sheet WHERE id = coalesce(NEW.sheet_id, OLD.sheet_id)) <> 'DRAFT' THEN
        RAISE EXCEPTION 'Salary sheet is locked';
    END IF;
    RETURN coalesce(NEW, OLD);
END
$$;

CREATE FUNCTION platform_delete_school(p_school_id BIGINT) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    pending  TEXT[] := '{}';
    blocked  TEXT[];
    stmt     TEXT;
    progress BOOLEAN;
    passes   INT := 0;
    removed  BIGINT := 0;
    n        BIGINT;
    r        RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM school WHERE id = p_school_id) THEN
        RAISE EXCEPTION 'School % not found', p_school_id USING ERRCODE = 'no_data_found';
    END IF;
    PERFORM set_config('app.deleting_school', p_school_id::text, true);

    UPDATE platform_audit_log SET school_id = NULL WHERE school_id = p_school_id;

    -- Rows without a school id that point at the school's rows (none today; kept for future tables)
    FOR r IN
        SELECT cl.relname AS child, a.attname AS col, pl.relname AS parent, pa.attname AS pcol
        FROM pg_constraint k
        JOIN pg_class cl ON cl.oid = k.conrelid
        JOIN pg_namespace ns ON ns.oid = cl.relnamespace
        JOIN pg_class pl ON pl.oid = k.confrelid
        JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = k.conkey[1]
        JOIN pg_attribute pa ON pa.attrelid = k.confrelid AND pa.attnum = k.confkey[1]
        WHERE k.contype = 'f' AND ns.nspname = 'public' AND cardinality(k.conkey) = 1 AND cl.relkind IN ('r', 'p')
          AND k.confdeltype NOT IN ('c', 'n', 'd')
          AND NOT EXISTS (SELECT 1 FROM pg_attribute x WHERE x.attrelid = k.conrelid AND x.attname = 'school_id' AND NOT x.attisdropped)
          AND EXISTS (SELECT 1 FROM pg_attribute y WHERE y.attrelid = k.confrelid AND y.attname = 'school_id' AND NOT y.attisdropped)
          AND pl.relname <> 'school'
        ORDER BY 1, 2
    LOOP
        pending := pending || format('DELETE FROM %I WHERE %I IN (SELECT %I FROM %I WHERE school_id = $1)',
                                     r.child, r.col, r.pcol, r.parent);
    END LOOP;

    -- Every table (not view) that carries the school id
    FOR r IN
        SELECT c.relname AS tbl
        FROM pg_class c
        JOIN pg_namespace ns ON ns.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'school_id' AND NOT a.attisdropped
        WHERE ns.nspname = 'public' AND c.relkind IN ('r', 'p') AND c.relname NOT IN ('school', 'platform_audit_log')
        ORDER BY 1
    LOOP
        pending := pending || format('DELETE FROM %I WHERE school_id = $1', r.tbl);
    END LOOP;

    WHILE cardinality(pending) > 0 AND passes < 64 LOOP
        passes := passes + 1;
        progress := false;
        blocked := '{}';
        FOREACH stmt IN ARRAY pending LOOP
            BEGIN
                EXECUTE stmt USING p_school_id;
                GET DIAGNOSTICS n = ROW_COUNT;
                removed := removed + n;
                progress := true;
            EXCEPTION WHEN foreign_key_violation THEN
                blocked := blocked || stmt;
            END;
        END LOOP;
        pending := blocked;
        EXIT WHEN NOT progress;
    END LOOP;
    IF cardinality(pending) > 0 THEN
        RAISE EXCEPTION 'Could not remove all rows of school %: %', p_school_id, pending[1]
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    DELETE FROM school WHERE id = p_school_id;
    PERFORM set_config('app.deleting_school', '', true);
    RETURN removed;
END
$$;

REVOKE ALL ON FUNCTION platform_delete_school(BIGINT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION platform_delete_school(BIGINT) TO sms_app;
