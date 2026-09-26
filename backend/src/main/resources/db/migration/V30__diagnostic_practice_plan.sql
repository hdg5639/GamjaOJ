CREATE TABLE diagnostic_practice_plan (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 evaluation_id UUID NOT NULL REFERENCES diagnostic_evaluation(id) ON DELETE CASCADE,
 observation_index INT NOT NULL CHECK(observation_index>=0),
 review_sha256 CHAR(64) NOT NULL,
 review_json TEXT NOT NULL,
 goal VARCHAR(120) NOT NULL,
 training_session_id UUID REFERENCES training_session(id) ON DELETE SET NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX diagnostic_plan_owner ON diagnostic_practice_plan(user_id,created_at);
