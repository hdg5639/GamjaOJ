-- Formal callable submissions pin their driver and tests without becoming transient custom runs.
ALTER TABLE submission ADD COLUMN callable_package TEXT;
ALTER TABLE submission ADD COLUMN callable_package_sha256 CHAR(64);
ALTER TABLE submission ADD CONSTRAINT submission_callable_package CHECK (
 (callable_package IS NULL AND callable_package_sha256 IS NULL) OR
 (callable_package IS NOT NULL AND callable_package_sha256 IS NOT NULL AND run_input IS NULL)
);
