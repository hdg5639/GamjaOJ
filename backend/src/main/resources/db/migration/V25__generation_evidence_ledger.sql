-- Append-only application evidence. Revocation never overwrites the original snapshot.
CREATE TABLE generation_evidence (
 id UUID PRIMARY KEY,
 job_id UUID NOT NULL REFERENCES generation_job(id) ON DELETE CASCADE,
 revision INT NOT NULL,
 snapshot_json TEXT NOT NULL,
 snapshot_sha256 CHAR(64) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(job_id,revision)
);
CREATE TABLE generation_evidence_revocation (
 evidence_id UUID PRIMARY KEY REFERENCES generation_evidence(id) ON DELETE CASCADE,
 root_job_id UUID NOT NULL,
 reason VARCHAR(500) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE generation_dependency (
 job_id UUID PRIMARY KEY REFERENCES generation_job(id) ON DELETE CASCADE,
 -- Deliberately retained if a source disappears: a missing source fails the fence.
 source_job_id UUID NOT NULL,
 source_revision INT NOT NULL,
 source_evidence_id UUID NOT NULL,
 source_evidence_sha256 CHAR(64) NOT NULL
);
CREATE INDEX generation_dependency_source ON generation_dependency(source_job_id);
