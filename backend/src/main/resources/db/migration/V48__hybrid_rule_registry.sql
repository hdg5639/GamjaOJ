-- Rule registry: families -> immutable versions -> problem instances. Built-ins are imported with their
-- existing ids; execution hashes are filled and verified from application code at startup.
CREATE TABLE hybrid_rule_family (
 id VARCHAR(80) PRIMARY KEY,
 visibility VARCHAR(16) NOT NULL CHECK (visibility IN ('BUILTIN')),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE hybrid_rule_version (
 id VARCHAR(80) PRIMARY KEY,
 family_id VARCHAR(80) NOT NULL REFERENCES hybrid_rule_family(id),
 engine VARCHAR(24) NOT NULL CHECK (engine IN ('BUILTIN_V1')),
 validation_policy VARCHAR(80) NOT NULL,
 profile_sha256 CHAR(64),
 contract_sha256 CHAR(64),
 status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','RETIRED','QUARANTINED')),
 status_reason VARCHAR(80),
 catalog_json TEXT,
 sort_order INT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO hybrid_rule_family(id,visibility) VALUES ('zero-one-items','BUILTIN'),('bfs-shortest-path','BUILTIN'),('dijkstra-shortest-path','BUILTIN');
INSERT INTO hybrid_rule_version(id,family_id,engine,validation_policy,status,sort_order) VALUES
 ('zero-one-items-v1','zero-one-items','BUILTIN_V1','hybrid-zero-one-items-v3','ACTIVE',10),
 ('bfs-shortest-path-v1','bfs-shortest-path','BUILTIN_V1','hybrid-bfs-shortest-path-v1','ACTIVE',20),
 ('dijkstra-shortest-path-v1','dijkstra-shortest-path','BUILTIN_V1','hybrid-dijkstra-shortest-path-v1','ACTIVE',30);

-- Reusable implementation that passed full validation and final review in a published instance.
-- Reuse never skips validation: every derived instance runs every Runner/review gate again.
CREATE TABLE hybrid_rule_artifact (
 id UUID PRIMARY KEY,
 rule_version_id VARCHAR(80) NOT NULL REFERENCES hybrid_rule_version(id),
 kind VARCHAR(24) NOT NULL CHECK (kind IN ('REFERENCE')),
 payload_json TEXT NOT NULL,
 payload_sha256 CHAR(64) NOT NULL,
 source_generation_id UUID NOT NULL REFERENCES hybrid_generation(id) ON DELETE CASCADE,
 source_branch_id UUID NOT NULL,
 source_version_id VARCHAR(80) NOT NULL,
 status VARCHAR(16) NOT NULL CHECK (status IN ('QUALIFIED','REVOKED')),
 qualified_at TIMESTAMP WITH TIME ZONE NOT NULL,
 revoked_at TIMESTAMP WITH TIME ZONE,
 revoke_reason VARCHAR(80),
 UNIQUE(rule_version_id,kind,payload_sha256)
);
CREATE INDEX hybrid_rule_artifact_version ON hybrid_rule_artifact(rule_version_id,status);

ALTER TABLE hybrid_public_request ADD COLUMN rule_version_id VARCHAR(80) REFERENCES hybrid_rule_version(id);
ALTER TABLE hybrid_public_request ADD COLUMN reference_artifact_id UUID REFERENCES hybrid_rule_artifact(id) ON DELETE SET NULL;
UPDATE hybrid_public_request SET rule_version_id=profile_id;
