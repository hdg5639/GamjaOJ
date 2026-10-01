-- OAuth credentials never leave the server; jobs are durable and independent of judge execution.
CREATE TABLE export_connection (
 id UUID NOT NULL UNIQUE,
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 provider VARCHAR(12) NOT NULL CHECK(provider IN ('GITHUB','NOTION')),
 credentials TEXT NOT NULL, account_label VARCHAR(200) NOT NULL,
 target_json TEXT, auto_enabled BOOLEAN NOT NULL DEFAULT false,
 status VARCHAR(24) NOT NULL DEFAULT 'CONNECTED',
 token_version INT NOT NULL DEFAULT 0, refreshing_until TIMESTAMP WITH TIME ZONE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(user_id,provider)
);
CREATE TABLE export_oauth (
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 provider VARCHAR(12) NOT NULL, state_sha256 CHAR(64) NOT NULL,
 verifier TEXT NOT NULL, claimed BOOLEAN NOT NULL DEFAULT false,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(user_id,provider), UNIQUE(state_sha256)
);
CREATE TABLE solution_export (
 id UUID PRIMARY KEY, user_id UUID NOT NULL,
 provider VARCHAR(12) NOT NULL, target_sha256 CHAR(64) NOT NULL, target_json TEXT NOT NULL,
 problem_version VARCHAR(80) NOT NULL, language VARCHAR(12) NOT NULL,
 submission_id UUID NOT NULL REFERENCES submission(id) ON DELETE CASCADE,
 submitted_at TIMESTAMP WITH TIME ZONE NOT NULL, payload_json TEXT NOT NULL,
 revision INT NOT NULL DEFAULT 0, status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
 attempts INT NOT NULL DEFAULT 0, next_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 lease_token UUID, lease_until TIMESTAMP WITH TIME ZONE,
 remote_json TEXT NOT NULL DEFAULT '{}', external_url VARCHAR(2000), error_code VARCHAR(80),
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(user_id,provider) REFERENCES export_connection(user_id,provider) ON DELETE CASCADE,
 UNIQUE(user_id,provider,target_sha256,problem_version,language)
);
CREATE INDEX solution_export_due ON solution_export(status,next_at);
CREATE INDEX solution_export_owner ON solution_export(user_id,updated_at);
