-- Explicit fixed-contract opt-in; never reinterpret a free-form request as this profile.
CREATE TABLE hybrid_public_request (
 generation_id UUID PRIMARY KEY REFERENCES hybrid_generation(id) ON DELETE CASCADE,
 profile_id VARCHAR(80) NOT NULL,
 profile_hash CHAR(64) NOT NULL,
 contract_sha256 CHAR(64) NOT NULL,
 handoff_mode VARCHAR(40) NOT NULL CHECK (handoff_mode='SERVER_FIXED_CONTRACT_V1')
);
