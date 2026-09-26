CREATE TABLE practice_followup (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 analysis_id UUID NOT NULL REFERENCES ai_task(id) ON DELETE CASCADE,
 step_index INT NOT NULL,
 goal TEXT NOT NULL,
 focus VARCHAR(80) NOT NULL,
 template_id VARCHAR(200),
 source_version VARCHAR(80) NOT NULL REFERENCES problem_version(id),
 session_id UUID REFERENCES training_session(id) ON DELETE SET NULL,
 generation_requested BOOLEAN NOT NULL DEFAULT FALSE,
 reviewed_submission_id UUID REFERENCES submission(id) ON DELETE SET NULL,
 used_help BOOLEAN,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 reviewed_at TIMESTAMP WITH TIME ZONE,
 UNIQUE(user_id,analysis_id,step_index,focus)
);
CREATE INDEX practice_followup_owner ON practice_followup(user_id,created_at);
