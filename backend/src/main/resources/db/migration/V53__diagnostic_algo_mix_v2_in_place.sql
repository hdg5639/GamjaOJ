-- algo-mix-a-v2 only adds public examples to algo-mix-a-v1 (same hidden tests, rubrics and categories).
-- Unfinished v1 sessions switch their still-open items to the matching v2 snapshot so learners see the new
-- examples immediately. Items with a submission still being judged keep v1: the Runner report is bound to
-- the package hash the job was queued with. Finished items and completed sessions are never rewritten.
-- SET expressions read the pre-update problem_version (portable to PostgreSQL and the H2 test database).
UPDATE diagnostic_item
SET package_json = (SELECT p.package_json FROM problem_version p
                    WHERE p.id = REPLACE(diagnostic_item.problem_version, 'diagnostic-algo-mix-a-v1-', 'diagnostic-algo-mix-a-v2-')),
    package_sha256 = (SELECT p.package_sha256 FROM problem_version p
                      WHERE p.id = REPLACE(diagnostic_item.problem_version, 'diagnostic-algo-mix-a-v1-', 'diagnostic-algo-mix-a-v2-')),
    runtime_image = (SELECT p.runtime_image FROM problem_version p
                     WHERE p.id = REPLACE(diagnostic_item.problem_version, 'diagnostic-algo-mix-a-v1-', 'diagnostic-algo-mix-a-v2-')),
    runner_policy = (SELECT p.runner_policy FROM problem_version p
                     WHERE p.id = REPLACE(diagnostic_item.problem_version, 'diagnostic-algo-mix-a-v1-', 'diagnostic-algo-mix-a-v2-')),
    rubric_json = (SELECT b.rubric_json FROM diagnostic_bank_item b
                   WHERE b.bank_id = 'algo-mix-a-v2'
                     AND b.problem_version = REPLACE(diagnostic_item.problem_version, 'diagnostic-algo-mix-a-v1-', 'diagnostic-algo-mix-a-v2-')),
    problem_version = REPLACE(problem_version, 'diagnostic-algo-mix-a-v1-', 'diagnostic-algo-mix-a-v2-')
WHERE status = 'OPEN'
  AND problem_version LIKE 'diagnostic-algo-mix-a-v1-%'
  AND session_id IN (SELECT s.id FROM diagnostic_session s WHERE s.status IN ('ACTIVE', 'PAUSED'))
  AND EXISTS (SELECT 1 FROM diagnostic_bank_item b JOIN problem_version p ON p.id = b.problem_version
              WHERE b.bank_id = 'algo-mix-a-v2'
                AND b.problem_version = REPLACE(diagnostic_item.problem_version, 'diagnostic-algo-mix-a-v1-', 'diagnostic-algo-mix-a-v2-')
                AND p.ready = true AND p.review_hold = false AND p.diagnostic_only = true)
  AND NOT EXISTS (SELECT 1 FROM submission x JOIN judge_job j ON j.submission_id = x.id
                  WHERE x.diagnostic_item_id = diagnostic_item.id AND j.status <> 'FINISHED');
