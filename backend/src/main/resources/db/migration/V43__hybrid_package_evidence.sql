CREATE TABLE hybrid_package_evidence (
 branch_id UUID PRIMARY KEY REFERENCES hybrid_branch(id) ON DELETE CASCADE,
 generator_seed BIGINT NOT NULL,
 random_seed BIGINT NOT NULL,
 candidates_json TEXT,
 candidates_sha256 CHAR(64),
 package_json TEXT,
 package_sha256 CHAR(64)
);
