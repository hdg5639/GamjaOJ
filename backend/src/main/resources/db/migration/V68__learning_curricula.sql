-- Keep existing manually confirmed plans and distinguish self-reported basic revision goals.
ALTER TABLE diagnostic_practice_plan ADD COLUMN source_kind VARCHAR(24) NOT NULL DEFAULT 'CODE_OBSERVATION';
ALTER TABLE diagnostic_practice_plan ADD CONSTRAINT diagnostic_plan_source_kind CHECK
    (source_kind IN ('CODE_OBSERVATION','SELF_REPORT'));
CREATE TABLE learning_curriculum_request (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 evaluation_id UUID NOT NULL REFERENCES diagnostic_evaluation(id) ON DELETE CASCADE,
 result_json TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
