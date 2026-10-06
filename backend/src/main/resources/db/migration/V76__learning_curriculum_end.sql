CREATE TABLE learning_curriculum_end (
 evaluation_id UUID PRIMARY KEY REFERENCES diagnostic_evaluation(id) ON DELETE CASCADE,
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 note VARCHAR(2000) NOT NULL,
 ended_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX learning_curriculum_end_owner ON learning_curriculum_end(user_id);
