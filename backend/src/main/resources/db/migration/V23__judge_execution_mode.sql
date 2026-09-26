-- Existing attempts remain exclusive; only newly admitted, trusted checks opt in.
ALTER TABLE judge_job ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'EXCLUSIVE'
 CHECK (execution_mode IN ('EXCLUSIVE','FUNCTIONAL'));
