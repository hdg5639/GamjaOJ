ALTER TABLE submission ADD COLUMN run_input TEXT;
ALTER TABLE submission ADD COLUMN run_package TEXT;
ALTER TABLE submission ADD COLUMN run_package_sha256 CHAR(64);
ALTER TABLE submission ADD CONSTRAINT submission_run_plan CHECK (
 (run_input IS NULL AND run_package IS NULL AND run_package_sha256 IS NULL) OR
 (run_input IS NOT NULL AND run_package IS NOT NULL AND run_package_sha256 IS NOT NULL)
);
