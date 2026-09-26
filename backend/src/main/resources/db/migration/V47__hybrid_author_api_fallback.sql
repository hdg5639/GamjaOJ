-- Author roles may be reserved in the existing API ledger when Codex is quota-limited.
ALTER TABLE hybrid_api_reservation RENAME TO hybrid_api_reservation_previous;
CREATE TABLE hybrid_api_reservation (
 attempt_id UUID PRIMARY KEY REFERENCES ai_attempt(id),
 generation_id UUID NOT NULL REFERENCES hybrid_generation(id) ON DELETE CASCADE,
 revision INT NOT NULL,
 role VARCHAR(24) NOT NULL CHECK (role IN ('CONTRACT','CORE','PRESENTATION','READER','CONTENT_REVIEW')),
 branch_id UUID REFERENCES hybrid_branch(id) ON DELETE SET NULL,
 assignment_json TEXT,
 receipt_json TEXT,
 UNIQUE(generation_id,revision,role), UNIQUE(branch_id)
);
INSERT INTO hybrid_api_reservation SELECT * FROM hybrid_api_reservation_previous;
DROP TABLE hybrid_api_reservation_previous;
CREATE INDEX hybrid_api_generation ON hybrid_api_reservation(generation_id);

-- Single-row observation of Codex quota exhaustion. Routing only; never evidence or approval.
CREATE TABLE hybrid_codex_quota (
 id INT PRIMARY KEY CHECK (id=1),
 observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 blocked_until TIMESTAMP WITH TIME ZONE NOT NULL
);
