ALTER TABLE diagnostic_practice_plan ADD COLUMN previous_plan_id UUID REFERENCES diagnostic_practice_plan(id);
CREATE UNIQUE INDEX diagnostic_plan_one_successor ON diagnostic_practice_plan(previous_plan_id);
ALTER TABLE diagnostic_practice_plan ADD COLUMN round_number INT NOT NULL DEFAULT 1 CHECK(round_number>0);
