-- Existing Java records and internal generation jobs retain their original image/policy.
ALTER TABLE submission ADD COLUMN language VARCHAR(16) NOT NULL DEFAULT 'JAVA';
ALTER TABLE submission ADD COLUMN execution_profile_json TEXT;
ALTER TABLE submission ADD CONSTRAINT submission_language CHECK (language IN ('JAVA','CPP','PYTHON'));
