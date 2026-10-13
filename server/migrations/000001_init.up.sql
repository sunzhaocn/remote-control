CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE devices (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    public_key_der bytea NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    credential_generation bigint NOT NULL DEFAULT 0
);
CREATE TABLE fixed_ids (
    normalized_id varchar(32) PRIMARY KEY,
    device_id uuid NOT NULL UNIQUE REFERENCES devices(id) ON DELETE CASCADE,
    code_hash text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_online_at timestamptz NOT NULL DEFAULT now(),
    CHECK (normalized_id = lower(btrim(normalized_id)))
);
CREATE INDEX fixed_ids_reclaim_idx ON fixed_ids(last_online_at);
