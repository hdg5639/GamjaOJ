ALTER TABLE generation_spec_draft ADD COLUMN final_token UUID;
ALTER TABLE generation_spec_draft ADD COLUMN final_completion_json TEXT;
ALTER TABLE generation_spec_draft ADD COLUMN final_plan_json TEXT;
ALTER TABLE generation_spec_draft ADD COLUMN final_plan_sha256 CHAR(64);
ALTER TABLE generation_spec_draft ADD COLUMN final_inputs_json TEXT;
ALTER TABLE generation_spec_draft ADD COLUMN final_report_json TEXT;
ALTER TABLE generation_spec_draft ADD COLUMN final_stage INTEGER;
