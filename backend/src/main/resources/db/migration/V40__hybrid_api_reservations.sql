-- Reuse the global monetary ledger; hybrid work is not learner feedback.
CREATE TABLE hybrid_api_reservation (
 attempt_id UUID PRIMARY KEY REFERENCES ai_attempt(id),
 generation_id UUID NOT NULL REFERENCES hybrid_generation(id) ON DELETE CASCADE,
 revision INT NOT NULL,
 role VARCHAR(24) NOT NULL CHECK (role IN ('PRESENTATION','READER')),
 branch_id UUID REFERENCES hybrid_branch(id) ON DELETE SET NULL,
 assignment_json TEXT,
 receipt_json TEXT,
 UNIQUE(generation_id,revision,role),
 UNIQUE(branch_id)
);
CREATE INDEX hybrid_api_generation ON hybrid_api_reservation(generation_id);

CREATE TABLE hybrid_execution_policy (
 generation_id UUID PRIMARY KEY REFERENCES hybrid_generation(id) ON DELETE CASCADE,
 codex_model VARCHAR(200) NOT NULL,
 codex_effort VARCHAR(24) NOT NULL
);
