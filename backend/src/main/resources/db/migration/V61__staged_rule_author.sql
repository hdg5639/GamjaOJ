-- Preserve all prior monolithic attempts/receipts while admitting independently fenced stages.
ALTER TABLE hybrid_rule_codex_call RENAME TO hybrid_rule_codex_call_previous;
CREATE TABLE hybrid_rule_codex_call (
 id UUID PRIMARY KEY,
 onboarding_id UUID NOT NULL REFERENCES hybrid_rule_onboarding(id) ON DELETE CASCADE,
 repair_round INT NOT NULL,
 stage VARCHAR(24) NOT NULL,
 stage_attempt INT NOT NULL,
 token UUID NOT NULL,
 input_json TEXT NOT NULL,
 input_sha256 CHAR(64) NOT NULL,
 model VARCHAR(100) NOT NULL,
 effort VARCHAR(24) NOT NULL,
 prompt_version VARCHAR(80) NOT NULL,
 status VARCHAR(24) NOT NULL,
 receipt_json TEXT,
 error_code VARCHAR(80),
 retry_requested_at TIMESTAMP WITH TIME ZONE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(onboarding_id,repair_round,stage,stage_attempt)
);
INSERT INTO hybrid_rule_codex_call(id,onboarding_id,repair_round,stage,stage_attempt,token,input_json,input_sha256,model,effort,prompt_version,status,receipt_json,created_at)
 SELECT id,onboarding_id,repair_round,'PACKAGE',0,token,input_json,input_sha256,model,effort,prompt_version,status,receipt_json,created_at
 FROM hybrid_rule_codex_call_previous;
DROP TABLE hybrid_rule_codex_call_previous;
CREATE TABLE hybrid_rule_author_stage (
 onboarding_id UUID NOT NULL REFERENCES hybrid_rule_onboarding(id) ON DELETE CASCADE,
 repair_round INT NOT NULL,
 stage VARCHAR(24) NOT NULL,
 call_id UUID NOT NULL REFERENCES hybrid_rule_codex_call(id) ON DELETE CASCADE,
 payload_json TEXT NOT NULL,
 payload_sha256 CHAR(64) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(onboarding_id,repair_round,stage)
);
