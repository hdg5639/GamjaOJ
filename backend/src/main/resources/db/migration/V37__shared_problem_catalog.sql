-- Existing personal problems remain private until their owner explicitly shares them.
ALTER TABLE problem_version ADD COLUMN shared BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE problem_version ADD COLUMN catalog_category VARCHAR(80);
ALTER TABLE problem_version ADD COLUMN catalog_tags VARCHAR(600);
ALTER TABLE problem_version ADD COLUMN catalog_difficulty VARCHAR(12);
ALTER TABLE generation_job ADD COLUMN share_on_publish BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE generation_spec_draft ADD COLUMN share_on_publish BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX problem_catalog_visibility ON problem_version(ready,diagnostic_only,shared);
