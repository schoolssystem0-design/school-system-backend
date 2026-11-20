-- Every school starts on a free trial when it is created. Schools made before that rule get their trial counted from
-- their creation day; if it is already over, they are blocked like any ended trial. Paying schools are left alone.
-- shortcut: 14 days is hard-coded (app.trial.days is not visible to SQL), change it here if that setting differs.
UPDATE school s
SET trial_ends_at = s.created_at + INTERVAL '14 days',
    updated_at    = now()
WHERE s.trial_ends_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM subscription sub WHERE sub.subscribed_school_id = s.id AND sub.status = 'ACTIVE');
