-- Explicitly align existing ongoing assessments with the calibrated problem budgets.
-- Completed assessments and already admitted submission execution profiles remain historical.
UPDATE diagnostic_item
SET time_limits_json = (
    SELECT p.time_limits_json FROM problem_version p
    WHERE p.id = diagnostic_item.problem_version
      AND p.package_sha256 = diagnostic_item.package_sha256
)
WHERE session_id IN (SELECT id FROM diagnostic_session WHERE status IN ('ACTIVE', 'PAUSED'))
  AND EXISTS (
    SELECT 1 FROM problem_version p
    WHERE p.id = diagnostic_item.problem_version
      AND p.package_sha256 = diagnostic_item.package_sha256
      AND p.time_limits_json IS NOT NULL
      AND diagnostic_item.time_limits_json IS DISTINCT FROM p.time_limits_json
  );
