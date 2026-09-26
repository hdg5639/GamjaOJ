-- Preserve existing reservations while extending the role constraint portably on PostgreSQL/H2.
ALTER TABLE hybrid_api_reservation RENAME TO hybrid_api_reservation_previous;
CREATE TABLE hybrid_api_reservation (
 attempt_id UUID PRIMARY KEY REFERENCES ai_attempt(id),
 generation_id UUID NOT NULL REFERENCES hybrid_generation(id) ON DELETE CASCADE,
 revision INT NOT NULL,
 role VARCHAR(24) NOT NULL CHECK (role IN ('PRESENTATION','READER','CONTENT_REVIEW')),
 branch_id UUID REFERENCES hybrid_branch(id) ON DELETE SET NULL,
 assignment_json TEXT,
 receipt_json TEXT,
 UNIQUE(generation_id,revision,role), UNIQUE(branch_id)
);
INSERT INTO hybrid_api_reservation SELECT * FROM hybrid_api_reservation_previous;
DROP TABLE hybrid_api_reservation_previous;
CREATE INDEX hybrid_api_generation ON hybrid_api_reservation(generation_id);
ALTER TABLE hybrid_generation ADD COLUMN published_version_id VARCHAR(80) REFERENCES problem_version(id);
