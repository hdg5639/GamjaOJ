-- Provision mappings separately; no candidate becomes reviewed through migration.
CREATE TABLE diagnostic_reassessment_pair (
 source_version VARCHAR(80) NOT NULL REFERENCES problem_version(id),
 target_version VARCHAR(80) NOT NULL REFERENCES problem_version(id),
 source_sha256 CHAR(64) NOT NULL,
 target_sha256 CHAR(64) NOT NULL,
 reviewed BOOLEAN NOT NULL DEFAULT false,
 PRIMARY KEY(source_version,target_version),
 CHECK(source_version<>target_version)
);
ALTER TABLE diagnostic_session ADD COLUMN source_session_id UUID REFERENCES diagnostic_session(id);
ALTER TABLE diagnostic_session ADD COLUMN correspondence_json TEXT;
CREATE TABLE diagnostic_exposure (
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 content_sha256 CHAR(64) NOT NULL,
 first_assigned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(user_id,content_sha256)
);
