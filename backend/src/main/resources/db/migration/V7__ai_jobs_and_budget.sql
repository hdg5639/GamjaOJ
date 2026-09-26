-- API spending is global; unresolved attempts retain their reservation across restarts/months.
CREATE TABLE ai_budget_lock (id INT PRIMARY KEY);
INSERT INTO ai_budget_lock VALUES (1);
CREATE TABLE ai_task (
 id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 submission_id UUID NOT NULL REFERENCES submission(id) ON DELETE CASCADE,
 kind VARCHAR(16) NOT NULL, cache_key CHAR(64) NOT NULL,
 settings_json TEXT NOT NULL, input_json TEXT NOT NULL,
 status VARCHAR(32) NOT NULL, result_json TEXT, error_code VARCHAR(80),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id, cache_key)
);
CREATE TABLE ai_attempt (
 id UUID PRIMARY KEY, task_id UUID REFERENCES ai_task(id) ON DELETE SET NULL,
 month_key CHAR(7) NOT NULL, status VARCHAR(32) NOT NULL,
 reserved_usd NUMERIC(16,8) NOT NULL CHECK(reserved_usd>=0),
 actual_usd NUMERIC(16,8), usage_json TEXT, request_id VARCHAR(200), response_id VARCHAR(200),
 settings_json TEXT NOT NULL, error_code VARCHAR(80),
 started_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 finished_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX ai_task_queue ON ai_task(status,created_at);
CREATE INDEX ai_attempt_budget ON ai_attempt(month_key,status);
CREATE TABLE ai_budget_notice (month_key CHAR(7) PRIMARY KEY, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP);
ALTER TABLE training_session ADD COLUMN analysis_task_id UUID;
ALTER TABLE training_session ADD COLUMN analysis_checked BOOLEAN NOT NULL DEFAULT FALSE;
-- Do not retroactively charge for sessions ended before this feature was installed.
UPDATE training_session SET analysis_checked=TRUE WHERE status='ENDED';
