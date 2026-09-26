-- Member-registered rule packages: private by default, explicitly shared by the owner.
ALTER TABLE hybrid_rule_family ADD COLUMN owner_id UUID REFERENCES app_user(id) ON DELETE CASCADE;
ALTER TABLE hybrid_rule_family ADD COLUMN shared BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE hybrid_rule_family RENAME TO hybrid_rule_family_previous;
CREATE TABLE hybrid_rule_family (
 id VARCHAR(80) PRIMARY KEY,
 visibility VARCHAR(16) NOT NULL CHECK (visibility IN ('BUILTIN','MEMBER')),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 owner_id UUID REFERENCES app_user(id) ON DELETE CASCADE,
 shared BOOLEAN NOT NULL DEFAULT false
);
INSERT INTO hybrid_rule_family(id,visibility,created_at,owner_id,shared) SELECT id,visibility,created_at,owner_id,shared FROM hybrid_rule_family_previous;

ALTER TABLE hybrid_rule_version RENAME TO hybrid_rule_version_previous;
CREATE TABLE hybrid_rule_version (
 id VARCHAR(80) PRIMARY KEY,
 family_id VARCHAR(80) NOT NULL REFERENCES hybrid_rule_family(id) ON DELETE CASCADE,
 engine VARCHAR(24) NOT NULL CHECK (engine IN ('BUILTIN_V1','PACKAGE_V1')),
 validation_policy VARCHAR(80) NOT NULL,
 profile_sha256 CHAR(64),
 contract_sha256 CHAR(64),
 status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','RETIRED','QUARANTINED')),
 status_reason VARCHAR(80),
 catalog_json TEXT,
 sort_order INT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 package_json TEXT
);
INSERT INTO hybrid_rule_version(id,family_id,engine,validation_policy,profile_sha256,contract_sha256,status,status_reason,catalog_json,sort_order,created_at,updated_at)
 SELECT id,family_id,engine,validation_policy,profile_sha256,contract_sha256,status,status_reason,catalog_json,sort_order,created_at,updated_at FROM hybrid_rule_version_previous;

-- Dependent tables are re-pointed at the replacement registry tables.
ALTER TABLE hybrid_rule_artifact RENAME TO hybrid_rule_artifact_previous;
CREATE TABLE hybrid_rule_onboarding (
 id UUID PRIMARY KEY,
 owner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 request_json TEXT NOT NULL,
 request_sha256 CHAR(64) NOT NULL,
 status VARCHAR(24) NOT NULL,
 error_code VARCHAR(80),
 author_json TEXT,
 author_sha256 CHAR(64),
 oracle_json TEXT,
 oracle_sha256 CHAR(64),
 carrier_generation_id UUID,
 answers_json TEXT,
 version_id VARCHAR(80),
 budget_usd DECIMAL(12,8) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX hybrid_rule_onboarding_owner ON hybrid_rule_onboarding(owner_id,created_at);
CREATE TABLE hybrid_rule_onboarding_call (
 attempt_id UUID PRIMARY KEY REFERENCES ai_attempt(id),
 onboarding_id UUID NOT NULL REFERENCES hybrid_rule_onboarding(id) ON DELETE CASCADE,
 role VARCHAR(24) NOT NULL CHECK (role IN ('AUTHOR','ORACLE')),
 receipt_json TEXT,
 UNIQUE(onboarding_id,role)
);
CREATE TABLE hybrid_rule_artifact (
 id UUID PRIMARY KEY,
 rule_version_id VARCHAR(80) NOT NULL REFERENCES hybrid_rule_version(id) ON DELETE CASCADE,
 kind VARCHAR(24) NOT NULL CHECK (kind IN ('REFERENCE')),
 payload_json TEXT NOT NULL,
 payload_sha256 CHAR(64) NOT NULL,
 source_generation_id UUID REFERENCES hybrid_generation(id) ON DELETE CASCADE,
 source_branch_id UUID,
 source_version_id VARCHAR(80),
 source_onboarding_id UUID REFERENCES hybrid_rule_onboarding(id) ON DELETE CASCADE,
 status VARCHAR(16) NOT NULL CHECK (status IN ('QUALIFIED','REVOKED')),
 qualified_at TIMESTAMP WITH TIME ZONE NOT NULL,
 revoked_at TIMESTAMP WITH TIME ZONE,
 revoke_reason VARCHAR(80),
 UNIQUE(rule_version_id,kind,payload_sha256),
 CHECK ((source_generation_id IS NOT NULL AND source_version_id IS NOT NULL) OR source_onboarding_id IS NOT NULL)
);
INSERT INTO hybrid_rule_artifact(id,rule_version_id,kind,payload_json,payload_sha256,source_generation_id,source_branch_id,source_version_id,status,qualified_at,revoked_at,revoke_reason)
 SELECT id,rule_version_id,kind,payload_json,payload_sha256,source_generation_id,source_branch_id,source_version_id,status,qualified_at,revoked_at,revoke_reason FROM hybrid_rule_artifact_previous;
CREATE INDEX hybrid_rule_artifact_version2 ON hybrid_rule_artifact(rule_version_id,status);

ALTER TABLE hybrid_public_request RENAME TO hybrid_public_request_previous;
CREATE TABLE hybrid_public_request (
 generation_id UUID PRIMARY KEY REFERENCES hybrid_generation(id) ON DELETE CASCADE,
 profile_id VARCHAR(80) NOT NULL,
 profile_hash CHAR(64) NOT NULL,
 contract_sha256 CHAR(64) NOT NULL,
 handoff_mode VARCHAR(40) NOT NULL CHECK (handoff_mode='SERVER_FIXED_CONTRACT_V1'),
 rule_version_id VARCHAR(80) REFERENCES hybrid_rule_version(id),
 reference_artifact_id UUID REFERENCES hybrid_rule_artifact(id) ON DELETE SET NULL
);
INSERT INTO hybrid_public_request SELECT * FROM hybrid_public_request_previous;
DROP TABLE hybrid_public_request_previous;
DROP TABLE hybrid_rule_artifact_previous;
DROP TABLE hybrid_rule_version_previous;
DROP TABLE hybrid_rule_family_previous;
