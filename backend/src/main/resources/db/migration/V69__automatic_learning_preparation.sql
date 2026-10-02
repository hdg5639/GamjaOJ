CREATE TABLE learning_problem_preparation (
    plan_id UUID PRIMARY KEY REFERENCES diagnostic_practice_plan(id) ON DELETE CASCADE,
    problem_version VARCHAR(80) REFERENCES problem_version(id) ON DELETE SET NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'WAITING',
    message VARCHAR(500),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
