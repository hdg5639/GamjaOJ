CREATE TABLE diagnostic_evaluation (
 id UUID PRIMARY KEY,
 session_id UUID NOT NULL REFERENCES diagnostic_session(id) ON DELETE CASCADE,
 evidence_sha256 CHAR(64) NOT NULL,
 evidence_json TEXT NOT NULL,
 facts_json TEXT NOT NULL,
 ai_task_id UUID REFERENCES ai_task(id) ON DELETE SET NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(session_id,evidence_sha256)
);
ALTER TABLE ai_task ADD COLUMN diagnostic_session_id UUID REFERENCES diagnostic_session(id) ON DELETE CASCADE;
