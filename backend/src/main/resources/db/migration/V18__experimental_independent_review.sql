ALTER TABLE generation_spec_draft ADD COLUMN review_token UUID;
ALTER TABLE generation_spec_draft ADD COLUMN review_completion_json TEXT;
ALTER TABLE generation_spec_draft ADD COLUMN review_payload_json TEXT;
ALTER TABLE generation_spec_draft ADD COLUMN review_payload_sha256 CHAR(64);
ALTER TABLE generation_spec_draft ADD COLUMN review_report_json TEXT;
