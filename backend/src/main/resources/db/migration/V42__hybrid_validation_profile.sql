CREATE TABLE hybrid_validation_profile (
 branch_id UUID PRIMARY KEY REFERENCES hybrid_branch(id) ON DELETE CASCADE,
 policy VARCHAR(64) NOT NULL,
 profile_hash CHAR(64)
);
-- Existing in-flight probes retain the policy they started under.
INSERT INTO hybrid_validation_profile(branch_id,policy)
SELECT id,'hybrid-execution-smoke-v1' FROM hybrid_branch
WHERE role='VALIDATION' AND status IN ('RUNNING','CHECKED');
