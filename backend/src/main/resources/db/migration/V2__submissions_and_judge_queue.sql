CREATE TABLE problem_version (
 id VARCHAR(80) PRIMARY KEY, package_json TEXT NOT NULL, package_sha256 CHAR(64) NOT NULL,
 runtime_image VARCHAR(200) NOT NULL, runner_policy VARCHAR(80) NOT NULL,
 ready BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE submission (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 problem_version VARCHAR(80) NOT NULL REFERENCES problem_version(id),
 source_code TEXT NOT NULL, source_sha256 CHAR(64) NOT NULL,
 idempotency_key UUID NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE (user_id, idempotency_key)
);
CREATE INDEX submission_user_created ON submission(user_id, created_at DESC);
CREATE TABLE judge_job (
 submission_id UUID PRIMARY KEY REFERENCES submission(id) ON DELETE CASCADE,
 status VARCHAR(16) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','FINISHED')),
 attempt INT NOT NULL DEFAULT 0, token UUID, worker_id UUID,
 lease_until TIMESTAMP WITH TIME ZONE, verdict VARCHAR(8), result_json TEXT, result_sha256 CHAR(64),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 finished_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX judge_job_queue ON judge_job(status, lease_until, created_at);
CREATE TABLE judge_queue_lock (id INT PRIMARY KEY);
INSERT INTO judge_queue_lock (id) VALUES (1);
CREATE TABLE judge_attempt (
 submission_id UUID NOT NULL REFERENCES submission(id) ON DELETE CASCADE,
 attempt INT NOT NULL, token UUID NOT NULL UNIQUE, worker_id UUID NOT NULL,
 status VARCHAR(16) NOT NULL, result_json TEXT,
 started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 finished_at TIMESTAMP WITH TIME ZONE,
 PRIMARY KEY(submission_id, attempt)
);
-- Only operator-controlled source hashes may execute while the shared-VM rollout is closed.
CREATE TABLE execution_grant (
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 source_sha256 CHAR(64) NOT NULL,
 PRIMARY KEY(user_id, source_sha256)
);
INSERT INTO problem_version (id,package_json,package_sha256,runtime_image,runner_policy) VALUES ('sum-v1','{"output_policy":"TOKEN_EXACT","statement":"공백으로 구분된 두 정수 A, B의 합을 출력한다. -1000000000 ≤ A, B ≤ 1000000000.","tests":[{"id":"sample","input":"1 2\n","output":"3\n"},{"id":"zero","input":"0 0\n","output":"0\n"},{"id":"positive-boundary","input":"1000000000 1000000000\n","output":"2000000000\n"},{"id":"negative-boundary","input":"-1000000000 -1000000000\n","output":"-2000000000\n"},{"id":"cancel","input":"-1000000000 1000000000\n","output":"0\n"}],"title":"두 정수의 합","version":"sum-v1"}','c2ec8513289fed8d78e731caa807edfa299c3fca981300a32dbe23cbc94c60b8','eclipse-temurin@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438','java21-m0-v2');
