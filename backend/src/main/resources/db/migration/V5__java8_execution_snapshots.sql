ALTER TABLE submission ADD COLUMN runtime_image VARCHAR(200);
ALTER TABLE submission ADD COLUMN runner_policy VARCHAR(80);
UPDATE submission SET runtime_image=(SELECT p.runtime_image FROM problem_version p WHERE p.id=submission.problem_version),
 runner_policy=CASE WHEN run_input IS NULL THEN (SELECT p.runner_policy FROM problem_version p WHERE p.id=submission.problem_version) ELSE 'java21-run-v1' END;
ALTER TABLE submission ALTER COLUMN runtime_image SET NOT NULL;
ALTER TABLE submission ALTER COLUMN runner_policy SET NOT NULL;
UPDATE problem_version SET runtime_image='eclipse-temurin@sha256:d57e5d0e3e5dd4cabb74feccfdb36249a58c76a3354a33ee8737813df93b7e0d',runner_policy='java8-judge-v1';
