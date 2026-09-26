ALTER TABLE diagnostic_practice_plan ADD COLUMN generation_id UUID REFERENCES generation_spec_draft(id);
ALTER TABLE diagnostic_practice_plan ADD COLUMN reviewed_submission_id UUID REFERENCES submission(id) ON DELETE SET NULL;
ALTER TABLE diagnostic_practice_plan ADD COLUMN used_help BOOLEAN;
ALTER TABLE diagnostic_practice_plan ADD COLUMN reflected_at TIMESTAMP WITH TIME ZONE;
