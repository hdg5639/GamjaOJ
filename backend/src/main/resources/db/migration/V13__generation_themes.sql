ALTER TABLE ai_task ALTER COLUMN submission_id DROP NOT NULL;
ALTER TABLE generation_job ADD COLUMN theme_task_id UUID REFERENCES ai_task(id);
ALTER TABLE generation_job ADD COLUMN theme_domain VARCHAR(80);
