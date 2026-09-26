CREATE TABLE generation_spec_draft (
 id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES app_user(id),
 request_text VARCHAR(2000) NOT NULL, status VARCHAR(32) NOT NULL,
 model VARCHAR(100) NOT NULL, effort VARCHAR(16) NOT NULL,
 token UUID, lease_until TIMESTAMP WITH TIME ZONE,
 spec_json TEXT, spec_sha256 CHAR(64), completion_json TEXT, error_code VARCHAR(80),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX generation_spec_draft_owner ON generation_spec_draft(owner_id,created_at);
