-- Presentation-only images never rewrite the validated problem package or judging evidence.
CREATE TABLE problem_illustration (
 id UUID PRIMARY KEY,
 problem_version VARCHAR(80) NOT NULL REFERENCES problem_version(id) ON DELETE CASCADE,
 user_id UUID REFERENCES app_user(id) ON DELETE SET NULL,
 package_sha256 CHAR(64) NOT NULL,
 image_sha256 CHAR(64) NOT NULL,
 alt VARCHAR(240) NOT NULL,
 caption VARCHAR(600) NOT NULL,
 image_png BYTEA NOT NULL,
 width INTEGER NOT NULL CHECK (width>0),
 height INTEGER NOT NULL CHECK (height>0),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX problem_illustration_version ON problem_illustration(problem_version,created_at);
CREATE INDEX problem_illustration_owner ON problem_illustration(user_id);
