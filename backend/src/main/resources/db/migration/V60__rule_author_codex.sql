-- Codex subscription execution is distinct from billed Responses API attempts.
CREATE TABLE hybrid_rule_codex_call (
 id UUID PRIMARY KEY,
 onboarding_id UUID NOT NULL REFERENCES hybrid_rule_onboarding(id) ON DELETE CASCADE,
 repair_round INT NOT NULL,
 token UUID NOT NULL,
 input_json TEXT NOT NULL,
 input_sha256 CHAR(64) NOT NULL,
 model VARCHAR(100) NOT NULL,
 effort VARCHAR(24) NOT NULL,
 prompt_version VARCHAR(80) NOT NULL,
 status VARCHAR(24) NOT NULL,
 receipt_json TEXT,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(onboarding_id,repair_round)
);
