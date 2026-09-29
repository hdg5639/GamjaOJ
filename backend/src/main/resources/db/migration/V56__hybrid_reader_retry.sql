-- A reader-code failure may be retried twice with fresh independent readers (same model, then a stronger one).
-- Each retry is its own budgeted reservation for the same role and revision.
ALTER TABLE hybrid_api_reservation RENAME TO hybrid_api_reservation_previous;
DROP INDEX IF EXISTS hybrid_api_generation;
CREATE TABLE hybrid_api_reservation (
 attempt_id UUID PRIMARY KEY REFERENCES ai_attempt(id),
 generation_id UUID NOT NULL REFERENCES hybrid_generation(id) ON DELETE CASCADE,
 revision INT NOT NULL,
 role VARCHAR(24) NOT NULL CHECK (role IN ('CONTRACT','CORE','PRESENTATION','READER','CONTENT_REVIEW')),
 branch_id UUID REFERENCES hybrid_branch(id) ON DELETE SET NULL,
 assignment_json TEXT,
 receipt_json TEXT,
 retry INT NOT NULL DEFAULT 0 CHECK (retry BETWEEN 0 AND 2),
 UNIQUE(generation_id,revision,role,retry), UNIQUE(branch_id)
);
INSERT INTO hybrid_api_reservation(attempt_id,generation_id,revision,role,branch_id,assignment_json,receipt_json)
 SELECT attempt_id,generation_id,revision,role,branch_id,assignment_json,receipt_json FROM hybrid_api_reservation_previous;
DROP TABLE hybrid_api_reservation_previous;
CREATE INDEX hybrid_api_generation ON hybrid_api_reservation(generation_id);
