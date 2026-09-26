CREATE TABLE generation_job (
 id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES app_user(id),
 template_id VARCHAR(80) NOT NULL, status VARCHAR(32) NOT NULL,
 model VARCHAR(100) NOT NULL, effort VARCHAR(16) NOT NULL,
 revision INT NOT NULL DEFAULT 0, token UUID, lease_until TIMESTAMP WITH TIME ZONE,
 artifacts_json TEXT, artifacts_sha256 CHAR(64), oracle_json TEXT,
 review_sha256 CHAR(64), validation_json TEXT, error_code VARCHAR(80),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE generation_execution (
 job_id UUID NOT NULL REFERENCES generation_job(id), revision INT NOT NULL,
 role VARCHAR(40) NOT NULL, submission_id UUID NOT NULL REFERENCES submission(id),
 expected_verdict VARCHAR(8) NOT NULL,
 PRIMARY KEY(job_id,revision,role)
);
CREATE TABLE generation_attempt (
 job_id UUID NOT NULL REFERENCES generation_job(id), revision INT NOT NULL,
 model VARCHAR(100) NOT NULL, effort VARCHAR(16) NOT NULL,
 billing_mode VARCHAR(32) NOT NULL DEFAULT 'CHATGPT_MANAGED',
 result_json TEXT, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(job_id,revision)
);
ALTER TABLE submission ADD COLUMN generation_job_id UUID REFERENCES generation_job(id);
ALTER TABLE judge_job ADD COLUMN priority INT NOT NULL DEFAULT 0;
ALTER TABLE problem_version ADD COLUMN teaching_json TEXT;
UPDATE problem_version SET teaching_json='{"hints":["입력에서 두 정수를 읽으세요.","두 수를 더한 값 하나만 출력하면 됩니다.","Scanner.nextInt()로 두 값을 읽고 합을 출력해 보세요."],"editorial":"두 정수를 읽어 합을 출력합니다. 두 입력은 각각 ±10억 이내이므로 합은 int 범위 안에 있습니다. 시간과 추가 공간은 O(1)입니다."}' WHERE id='sum-v1';
UPDATE problem_version SET teaching_json='{"hints":["첫 번째 정수 N은 더할 숫자의 개수입니다.","N번 반복하며 읽은 값을 누적하세요.","입력 하나는 int 범위지만 전체 합은 아닐 수 있습니다. 누적 변수에 long을 사용하세요."],"editorial":"N을 읽고 N개의 정수를 long 변수에 누적합니다. 최대 합의 절댓값은 10의 12제곱이므로 int 누적은 오버플로가 발생합니다. 시간 O(N), 추가 공간 O(1)입니다."}' WHERE id='total-v1';
UPDATE problem_version SET teaching_json='{"hints":["왼쪽부터 읽으며 아직 닫히지 않은 괄호 수를 세세요.","닫는 괄호를 만났는데 열린 괄호가 없다면 이미 잘못된 문자열입니다.","어느 접두 구간에서도 개수가 음수가 아니고, 끝에서 0이어야 합니다."],"editorial":"여는 괄호에는 1을 더하고 닫는 괄호에는 1을 뺍니다. 중간에 음수가 되면 NO입니다. 끝에서 0이면 YES, 아니면 NO입니다. 두 괄호의 전체 개수만 비교하면 순서를 놓칩니다. 시간 O(N), 추가 공간 O(1)입니다."}' WHERE id='valid-parentheses-v1';
