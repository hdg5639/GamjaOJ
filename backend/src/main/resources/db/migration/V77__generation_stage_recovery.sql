ALTER TABLE generation_spec_draft ADD COLUMN auto_recovery BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE generation_spec_draft ADD COLUMN recovery_scope VARCHAR(24);
ALTER TABLE generation_spec_draft ADD COLUMN recovery_feedback TEXT;
ALTER TABLE generation_spec_draft ADD COLUMN retry_after TIMESTAMP WITH TIME ZONE;
CREATE TABLE generation_recovery_attempt (
 pipeline VARCHAR(24) NOT NULL, job_id UUID NOT NULL, attempt INTEGER NOT NULL,
 scope VARCHAR(24) NOT NULL, error_code VARCHAR(100), snapshot_json TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (pipeline,job_id,attempt)
);
CREATE TABLE generation_recovery_receipt (
 job_id UUID NOT NULL, token UUID NOT NULL, completion_json TEXT NOT NULL,
 PRIMARY KEY(job_id,token)
);
ALTER TABLE generation_job ADD COLUMN resource_validation BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE generation_spec_draft ADD COLUMN resource_validation BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE hybrid_generation ADD COLUMN resource_validation BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE generation_resource_check (
 id UUID PRIMARY KEY, pipeline VARCHAR(24) NOT NULL, job_id UUID NOT NULL,
 problem_version VARCHAR(100) NOT NULL REFERENCES problem_version(id),
 fence CHAR(64) NOT NULL, input_json TEXT NOT NULL, status VARCHAR(24) NOT NULL,
 token UUID, lease_until TIMESTAMP WITH TIME ZONE, completion_json TEXT,
 artifacts_json TEXT, limits_json TEXT, report_json TEXT, error_code VARCHAR(100),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(pipeline,job_id,fence)
);
CREATE TABLE generation_resource_execution (
 check_id UUID NOT NULL REFERENCES generation_resource_check(id),
 role VARCHAR(48) NOT NULL, submission_id UUID NOT NULL REFERENCES submission(id),
 PRIMARY KEY(check_id,role)
);
ALTER TABLE generation_resource_check ADD COLUMN retries INTEGER NOT NULL DEFAULT 0;
CREATE TABLE generation_resource_attempt (
 check_id UUID NOT NULL REFERENCES generation_resource_check(id), attempt INTEGER NOT NULL,
 completion_json TEXT, artifacts_json TEXT, report_json TEXT NOT NULL,
 PRIMARY KEY(check_id,attempt)
);
-- Reservations may skip an unused slot after a writer/core repair. Old receipts stay intact.
ALTER TABLE hybrid_api_reservation RENAME TO hybrid_api_reservation_stage_previous;
DROP INDEX IF EXISTS hybrid_api_generation;
CREATE TABLE hybrid_api_reservation (
 attempt_id UUID PRIMARY KEY REFERENCES ai_attempt(id),
 generation_id UUID NOT NULL REFERENCES hybrid_generation(id) ON DELETE CASCADE,
 revision INT NOT NULL,
 role VARCHAR(24) NOT NULL CHECK (role IN ('CONTRACT','CORE','PRESENTATION','READER','CONTENT_REVIEW')),
 branch_id UUID REFERENCES hybrid_branch(id) ON DELETE SET NULL,
 assignment_json TEXT, receipt_json TEXT,
 retry INT NOT NULL DEFAULT 0 CHECK (retry BETWEEN 0 AND 6),
 UNIQUE(generation_id,revision,role,retry), UNIQUE(branch_id)
);
INSERT INTO hybrid_api_reservation(attempt_id,generation_id,revision,role,branch_id,assignment_json,receipt_json,retry)
 SELECT attempt_id,generation_id,revision,role,branch_id,assignment_json,receipt_json,retry FROM hybrid_api_reservation_stage_previous;
DROP TABLE hybrid_api_reservation_stage_previous;
CREATE INDEX hybrid_api_generation ON hybrid_api_reservation(generation_id);
CREATE TABLE generation_prose_review (
 id UUID PRIMARY KEY, job_id UUID NOT NULL REFERENCES generation_job(id), revision INTEGER NOT NULL,
 artifact_hash CHAR(64) NOT NULL, input_json TEXT NOT NULL,
 status VARCHAR(24) NOT NULL, token UUID, lease_until TIMESTAMP WITH TIME ZONE,
 completion_json TEXT, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(job_id,revision)
);
