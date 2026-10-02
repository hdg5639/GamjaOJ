ALTER TABLE problem_thinking_profile ADD COLUMN assessment_kind VARCHAR(16) CHECK (assessment_kind IN ('MODEL','TEMPLATE','IMPORT'));
ALTER TABLE generation_spec_draft ADD COLUMN review_thinking BOOLEAN NOT NULL DEFAULT false;
