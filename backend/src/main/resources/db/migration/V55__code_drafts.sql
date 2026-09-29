-- Server copy of the code a member is writing, per problem (or diagnostic item) and language.
CREATE TABLE code_draft (
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 scope VARCHAR(120) NOT NULL,
 language VARCHAR(16) NOT NULL,
 source TEXT NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(user_id,scope,language)
);
CREATE INDEX code_draft_recent ON code_draft(user_id,updated_at);
