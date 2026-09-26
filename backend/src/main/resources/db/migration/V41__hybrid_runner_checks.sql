CREATE TABLE hybrid_execution_check (
 branch_id UUID NOT NULL REFERENCES hybrid_branch(id) ON DELETE CASCADE,
 role VARCHAR(64) NOT NULL,
 submission_id UUID NOT NULL REFERENCES submission(id) ON DELETE CASCADE,
 source_sha256 CHAR(64) NOT NULL,
 package_sha256 CHAR(64) NOT NULL,
 PRIMARY KEY(branch_id,role)
);
ALTER TABLE submission ADD COLUMN hybrid_branch_id UUID REFERENCES hybrid_branch(id) ON DELETE SET NULL;
