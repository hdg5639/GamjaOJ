-- Curated courses are independent of diagnostic evaluations; the enrolled definition is immutable.
CREATE TABLE training_course_enrollment (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 course_id VARCHAR(64) NOT NULL,
 revision INTEGER NOT NULL CHECK (revision>0),
 course_json TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,course_id,revision)
);
CREATE INDEX training_course_owner ON training_course_enrollment(user_id,created_at);
CREATE TABLE training_course_session (
 session_id UUID PRIMARY KEY REFERENCES training_session(id) ON DELETE CASCADE,
 enrollment_id UUID NOT NULL REFERENCES training_course_enrollment(id) ON DELETE CASCADE,
 position INTEGER NOT NULL CHECK (position>=0)
);
CREATE INDEX training_course_steps ON training_course_session(enrollment_id,position);
CREATE TABLE training_course_request (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 request_json TEXT NOT NULL,
 enrollment_id UUID NOT NULL REFERENCES training_course_enrollment(id) ON DELETE CASCADE,
 session_id UUID REFERENCES training_session(id) ON DELETE CASCADE
);
