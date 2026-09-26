-- Additive contract/branch foundation. No provider task or published package is created here.
CREATE TABLE hybrid_generation (
 id UUID PRIMARY KEY,
 owner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 pipeline_version VARCHAR(32) NOT NULL,
 request_json TEXT NOT NULL,
 request_sha256 CHAR(64) NOT NULL,
 revision INT NOT NULL DEFAULT 0,
 status VARCHAR(32) NOT NULL,
 repair_rounds INT NOT NULL DEFAULT 0 CHECK (repair_rounds BETWEEN 0 AND 1),
 share_on_publish BOOLEAN NOT NULL DEFAULT FALSE,
 contract_sha256 CHAR(64),
 public_sha256 CHAR(64),
 error_code VARCHAR(80),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE hybrid_branch (
 id UUID PRIMARY KEY,
 generation_id UUID NOT NULL REFERENCES hybrid_generation(id) ON DELETE CASCADE,
 revision INT NOT NULL,
 role VARCHAR(24) NOT NULL,
 attempt INT NOT NULL,
 status VARCHAR(24) NOT NULL,
 input_json TEXT NOT NULL,
 input_sha256 CHAR(64) NOT NULL,
 contract_sha256 CHAR(64),
 public_sha256 CHAR(64),
 attempt_token UUID,
 completion_json TEXT,
 output_sha256 CHAR(64),
 error_code VARCHAR(80),
 late_result BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 started_at TIMESTAMP WITH TIME ZONE,
 finished_at TIMESTAMP WITH TIME ZONE,
 UNIQUE(generation_id,revision,role,attempt)
);
CREATE INDEX hybrid_branch_queue ON hybrid_branch(status,created_at);
CREATE TABLE hybrid_artifact (
 branch_id UUID PRIMARY KEY REFERENCES hybrid_branch(id) ON DELETE CASCADE,
 schema_version VARCHAR(16) NOT NULL,
 prompt_version VARCHAR(48) NOT NULL,
 payload_json TEXT NOT NULL,
 payload_sha256 CHAR(64) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
