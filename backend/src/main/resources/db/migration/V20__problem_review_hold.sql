ALTER TABLE problem_version ADD COLUMN review_hold BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE problem_version ADD COLUMN review_reason VARCHAR(500);
ALTER TABLE problem_version ADD COLUMN review_held_at TIMESTAMP WITH TIME ZONE;
