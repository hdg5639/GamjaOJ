CREATE TABLE support_request (
 id UUID PRIMARY KEY,
 owner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 kind VARCHAR(16) NOT NULL CHECK (kind IN ('오류 제보','이용 문의','개선 제안')),
 title VARCHAR(120) NOT NULL, body VARCHAR(4000) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED')),
 reply VARCHAR(4000) NOT NULL DEFAULT '', revision INTEGER NOT NULL DEFAULT 0,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX support_owner_recent ON support_request(owner_id,created_at);
CREATE INDEX support_status_recent ON support_request(status,created_at);
