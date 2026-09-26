-- Append-only user reports; never overwrite judge evidence or provider interpretations.
CREATE TABLE diagnostic_correction (
 id UUID PRIMARY KEY,
 evaluation_id UUID NOT NULL REFERENCES diagnostic_evaluation(id) ON DELETE CASCADE,
 request_key UUID NOT NULL,
 observation_index INT NOT NULL CHECK(observation_index>=0),
 interpretation_sha256 CHAR(64) NOT NULL,
 note VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(evaluation_id,request_key)
);
CREATE INDEX diagnostic_correction_evaluation ON diagnostic_correction(evaluation_id,created_at);
