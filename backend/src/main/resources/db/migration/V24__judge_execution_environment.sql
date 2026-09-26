-- Per-attempt snapshot: deployment must not change the contract of a resumed attempt.
-- Legacy running attempts remain NULL and cannot become environment-backed evidence.
ALTER TABLE judge_attempt ADD COLUMN execution_environment_json TEXT;
