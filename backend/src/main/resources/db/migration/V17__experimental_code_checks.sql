ALTER TABLE generation_spec_draft ADD COLUMN build_token UUID;
ALTER TABLE generation_spec_draft ADD COLUMN build_completion_json TEXT;
ALTER TABLE generation_spec_draft ADD COLUMN build_artifacts_json TEXT;
ALTER TABLE generation_spec_draft ADD COLUMN build_oracle_json TEXT;
ALTER TABLE generation_spec_draft ADD COLUMN build_sha256 CHAR(64);
ALTER TABLE generation_spec_draft ADD COLUMN build_report_json TEXT;
ALTER TABLE generation_spec_draft ADD COLUMN build_inputs_json TEXT;
ALTER TABLE submission ADD COLUMN spec_draft_id UUID REFERENCES generation_spec_draft(id);
CREATE TABLE generation_spec_execution (
 draft_id UUID NOT NULL REFERENCES generation_spec_draft(id),
 role VARCHAR(40) NOT NULL,
 submission_id UUID NOT NULL REFERENCES submission(id),
 expected_verdict VARCHAR(8) NOT NULL,
 PRIMARY KEY(draft_id,role)
);
