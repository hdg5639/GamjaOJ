ALTER TABLE problem_version ADD COLUMN owner_id UUID REFERENCES app_user(id);
ALTER TABLE generation_job ADD COLUMN focus VARCHAR(40) NOT NULL DEFAULT 'basics';
UPDATE problem_version SET owner_id=(SELECT g.owner_id FROM generation_job g WHERE problem_version.id=CONCAT(CONCAT(CONCAT('generated-',CAST(g.id AS VARCHAR(36))),'-r'),CAST(g.revision AS VARCHAR(10)))) WHERE id LIKE 'generated-%';
