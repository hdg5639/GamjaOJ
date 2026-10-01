-- Shared setup state prevents another delivery from repeating an uncertain database creation.
CREATE TABLE notion_export_table (
 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 parent_id VARCHAR(80) NOT NULL,
 remote_json TEXT NOT NULL DEFAULT '{}',
 lease_token UUID, lease_until TIMESTAMP WITH TIME ZONE,
 PRIMARY KEY(user_id,parent_id)
);
