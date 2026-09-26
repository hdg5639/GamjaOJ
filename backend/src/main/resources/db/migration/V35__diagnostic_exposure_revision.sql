ALTER TABLE diagnostic_session ADD COLUMN exposure_revision INT NOT NULL DEFAULT 0;
ALTER TABLE diagnostic_evaluation ADD COLUMN exposure_revision INT NOT NULL DEFAULT 0;
ALTER TABLE diagnostic_item ADD COLUMN exposure_reported_at TIMESTAMP WITH TIME ZONE;
