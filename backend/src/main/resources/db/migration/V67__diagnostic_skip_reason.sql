ALTER TABLE diagnostic_item ADD COLUMN skip_reason VARCHAR(24);
ALTER TABLE diagnostic_item ADD CONSTRAINT diagnostic_skip_reason_valid CHECK
    (skip_reason IS NULL OR skip_reason IN ('NOT_SURE','NO_TIME','OTHER','UNSPECIFIED','SESSION_ENDED'));
