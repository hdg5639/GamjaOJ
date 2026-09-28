-- One author repair round: the failed check and previous package go back to the author once; every check reruns.
ALTER TABLE hybrid_rule_onboarding ADD COLUMN repairs INT NOT NULL DEFAULT 0;
ALTER TABLE hybrid_rule_onboarding ADD COLUMN repair_json TEXT;
-- Model calls are unique per role and repair round (the original UNIQUE(onboarding_id, role) allowed one author call).
CREATE TABLE hybrid_rule_onboarding_call_next (
 attempt_id UUID PRIMARY KEY REFERENCES ai_attempt(id),
 onboarding_id UUID NOT NULL REFERENCES hybrid_rule_onboarding(id) ON DELETE CASCADE,
 role VARCHAR(24) NOT NULL CHECK (role IN ('AUTHOR','ORACLE')),
 repair_round INT NOT NULL DEFAULT 0,
 receipt_json TEXT,
 UNIQUE(onboarding_id,role,repair_round)
);
INSERT INTO hybrid_rule_onboarding_call_next(attempt_id,onboarding_id,role,repair_round,receipt_json)
 SELECT attempt_id,onboarding_id,role,0,receipt_json FROM hybrid_rule_onboarding_call;
DROP TABLE hybrid_rule_onboarding_call;
ALTER TABLE hybrid_rule_onboarding_call_next RENAME TO hybrid_rule_onboarding_call;
