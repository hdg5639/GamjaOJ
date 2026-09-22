CREATE TABLE training_session (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 problem_version VARCHAR(80) NOT NULL REFERENCES problem_version(id),
 goal VARCHAR(120) NOT NULL,
 note VARCHAR(2000) NOT NULL DEFAULT '',
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ENDED')),
 active_owner UUID UNIQUE,
 started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 ended_at TIMESTAMP WITH TIME ZONE,
 CHECK ((status='ACTIVE' AND active_owner=user_id AND active_owner IS NOT NULL AND ended_at IS NULL)
     OR (status='ENDED' AND active_owner IS NULL AND ended_at IS NOT NULL))
);
CREATE INDEX training_session_owner ON training_session(user_id,started_at);
ALTER TABLE submission ADD COLUMN training_session_id UUID REFERENCES training_session(id) ON DELETE CASCADE;
CREATE INDEX submission_session ON submission(training_session_id);
