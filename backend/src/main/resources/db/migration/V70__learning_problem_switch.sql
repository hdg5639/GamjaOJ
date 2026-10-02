CREATE TABLE learning_problem_switch (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 request_json TEXT NOT NULL,
 result_plan_id UUID NOT NULL REFERENCES diagnostic_practice_plan(id) ON DELETE CASCADE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
