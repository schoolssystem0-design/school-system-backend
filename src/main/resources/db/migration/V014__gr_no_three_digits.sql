-- GR numbers are shown with at least three digits: 001, 002 ... 010 ... 100, 101.
-- Pads the existing automatic (numbers-only) GR numbers; skips any that would clash with a GR number already in use.
UPDATE student s
SET gr_no = lpad(s.gr_no, 3, '0')
WHERE s.gr_no ~ '^[0-9]{1,2}$'
  AND NOT EXISTS (SELECT 1 FROM student o WHERE o.school_id = s.school_id AND o.gr_no = lpad(s.gr_no, 3, '0'));
