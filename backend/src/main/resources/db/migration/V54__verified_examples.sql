-- Worked examples written by the independent reader, shown only after the Runner confirms them with the
-- problem's input validator and reference. Kept outside the judged package.
ALTER TABLE problem_version ADD COLUMN examples_json TEXT;
ALTER TABLE problem_version ADD COLUMN examples_status VARCHAR(16);
ALTER TABLE submission ADD COLUMN example_check BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE example_check (
 problem_version VARCHAR(80) NOT NULL REFERENCES problem_version(id) ON DELETE CASCADE,
 position INT NOT NULL,
 role VARCHAR(16) NOT NULL CHECK (role IN ('VALID','REFERENCE')),
 submission_id UUID NOT NULL REFERENCES submission(id) ON DELETE CASCADE,
 PRIMARY KEY(problem_version,position,role)
);
