DO $$
BEGIN
    IF current_schema() = 'public' THEN
        CREATE TABLE owner_phone_access (
            tenant_id UUID PRIMARY KEY REFERENCES rush_tenants(id) ON DELETE CASCADE,
            code_hash TEXT,
            code_expires_at TIMESTAMPTZ,
            issue_window_at TIMESTAMPTZ,
            issued_count SMALLINT NOT NULL DEFAULT 0,
            attempt_window_at TIMESTAMPTZ,
            failed_count SMALLINT NOT NULL DEFAULT 0,
            locked_until TIMESTAMPTZ
        );

        CREATE TABLE owner_phone_sessions (
            call_hash TEXT PRIMARY KEY CHECK (call_hash ~ '^[a-f0-9]{64}$'),
            tenant_id UUID NOT NULL REFERENCES rush_tenants(id) ON DELETE CASCADE,
            expires_at TIMESTAMPTZ NOT NULL
        );
        CREATE INDEX owner_phone_sessions_expiry ON owner_phone_sessions(expires_at);

        CREATE TABLE owner_phone_mutations (
            call_hash TEXT NOT NULL REFERENCES owner_phone_sessions(call_hash) ON DELETE CASCADE,
            sequence INTEGER NOT NULL CHECK (sequence BETWEEN 1 AND 1000),
            challenge TEXT NOT NULL CHECK (challenge ~ '^[1-9][0-9]{3}$'),
            proposed JSONB NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL,
            state TEXT NOT NULL CHECK (state IN ('PENDING','APPLIED','CANCELLED','CHANGED')),
            outcome JSONB,
            PRIMARY KEY (call_hash,sequence),
            UNIQUE (call_hash,challenge)
        );
        CREATE UNIQUE INDEX owner_phone_single_pending ON owner_phone_mutations(call_hash) WHERE state='PENDING';
    END IF;
END $$;
