-- Private original intent is frozen at rule-generation admission, independently of rule/source deletion.
ALTER TABLE hybrid_public_request ADD COLUMN requirements_json TEXT;
ALTER TABLE hybrid_public_request ADD COLUMN requirements_sha256 VARCHAR(64);
-- Old in-flight independent reviews retain their original output contract.
ALTER TABLE generation_spec_draft ADD COLUMN review_requirements BOOLEAN NOT NULL DEFAULT false;
