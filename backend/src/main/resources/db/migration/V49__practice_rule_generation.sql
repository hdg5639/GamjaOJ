-- Diagnostic practice may request a registered-rule problem instead of a free-form draft.
ALTER TABLE diagnostic_practice_plan ADD COLUMN hybrid_generation_id UUID REFERENCES hybrid_generation(id) ON DELETE SET NULL;
