-- Reviewed banks are provisioned separately; migration installs no assessment content.
ALTER TABLE problem_version ADD COLUMN diagnostic_only BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE diagnostic_bank (
 id VARCHAR(80) PRIMARY KEY,
 reviewed BOOLEAN NOT NULL DEFAULT false
);
CREATE TABLE diagnostic_bank_item (
 bank_id VARCHAR(80) NOT NULL REFERENCES diagnostic_bank(id),
 position INTEGER NOT NULL CHECK (position >= 0),
 category VARCHAR(80) NOT NULL,
 difficulty VARCHAR(8) NOT NULL CHECK (difficulty IN ('EASY','MEDIUM')),
 problem_version VARCHAR(80) NOT NULL REFERENCES problem_version(id),
 rubric_json TEXT NOT NULL,
 PRIMARY KEY(bank_id,position),
 UNIQUE(bank_id,category,difficulty),
 UNIQUE(bank_id,problem_version)
);
CREATE TABLE diagnostic_session (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 bank_id VARCHAR(80) NOT NULL REFERENCES diagnostic_bank(id),
 status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','PAUSED','COMPLETED')),
 open_owner UUID UNIQUE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK ((status IN ('ACTIVE','PAUSED') AND open_owner IS NOT NULL AND open_owner=user_id)
     OR (status='COMPLETED' AND open_owner IS NULL))
);
CREATE TABLE diagnostic_item (
 id UUID PRIMARY KEY,
 session_id UUID NOT NULL REFERENCES diagnostic_session(id) ON DELETE CASCADE,
 position INTEGER NOT NULL,
 category VARCHAR(80) NOT NULL,
 difficulty VARCHAR(8) NOT NULL,
 problem_version VARCHAR(80) NOT NULL REFERENCES problem_version(id),
 package_json TEXT NOT NULL,
 package_sha256 CHAR(64) NOT NULL,
 runtime_image VARCHAR(255) NOT NULL,
 runner_policy VARCHAR(80) NOT NULL,
 rubric_json TEXT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','PASSED','EXHAUSTED','SKIPPED')),
 UNIQUE(session_id,position)
);
ALTER TABLE submission ADD COLUMN diagnostic_item_id UUID REFERENCES diagnostic_item(id) ON DELETE CASCADE;
CREATE INDEX submission_diagnostic ON submission(diagnostic_item_id);
