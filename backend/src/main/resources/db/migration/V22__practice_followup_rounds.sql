ALTER TABLE practice_followup ADD COLUMN round_number INT NOT NULL DEFAULT 1;
ALTER TABLE practice_followup ADD COLUMN round_id UUID;
UPDATE practice_followup SET round_id=id;
ALTER TABLE practice_followup ALTER COLUMN round_id SET NOT NULL;
CREATE TABLE practice_followup_attempt (
 followup_id UUID NOT NULL REFERENCES practice_followup(id) ON DELETE CASCADE,
 round_number INT NOT NULL,
 session_id UUID NOT NULL REFERENCES training_session(id) ON DELETE CASCADE,
 generation_id UUID,
 reviewed_submission_id UUID REFERENCES submission(id) ON DELETE SET NULL,
 used_help BOOLEAN,
 reviewed_at TIMESTAMP WITH TIME ZONE,
 PRIMARY KEY(followup_id,round_number),
 UNIQUE(session_id)
);
