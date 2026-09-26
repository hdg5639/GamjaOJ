ALTER TABLE generation_job ADD COLUMN structure_contract CHAR(64);
ALTER TABLE generation_job ADD COLUMN structure_tags_json TEXT;
ALTER TABLE generation_job ADD COLUMN structure_reuse_json TEXT;
CREATE INDEX generation_structure_lookup ON generation_job(owner_id, template_id, structure_contract, status);
