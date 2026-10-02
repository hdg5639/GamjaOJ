CREATE TABLE problem_reflection (
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 problem_version VARCHAR(80) NOT NULL REFERENCES problem_version(id) ON DELETE CASCADE,
 submission_id UUID REFERENCES submission(id) ON DELETE SET NULL,
 confidence VARCHAR(16) NOT NULL CHECK (confidence IN ('SOLID','SHAKY','REVISIT')),
 note VARCHAR(500) NOT NULL DEFAULT '',
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(user_id,problem_version)
);
CREATE INDEX problem_reflection_review ON problem_reflection(user_id,confidence,updated_at);
