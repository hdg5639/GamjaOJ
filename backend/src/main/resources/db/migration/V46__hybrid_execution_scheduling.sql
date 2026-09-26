-- Existing validation evidence keeps its serial execution contract.
ALTER TABLE hybrid_validation_profile ADD COLUMN scheduling VARCHAR(32) NOT NULL DEFAULT 'SERIAL_V1';
