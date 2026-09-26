ALTER TABLE ai_attempt ADD COLUMN provider_model VARCHAR(200);
ALTER TABLE generation_attempt ADD COLUMN executor VARCHAR(32) NOT NULL DEFAULT 'CODEX_CLI';
ALTER TABLE generation_attempt ADD COLUMN prompt_version VARCHAR(80) NOT NULL DEFAULT 'sequence-sum-author-oracle-v1';
ALTER TABLE generation_attempt ADD COLUMN schema_version VARCHAR(80) NOT NULL DEFAULT 'generation-artifacts-v1';
ALTER TABLE generation_attempt ADD COLUMN cli_version VARCHAR(40) NOT NULL DEFAULT '0.154.0';
