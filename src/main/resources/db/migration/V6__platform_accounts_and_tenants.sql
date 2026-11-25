-- Every shop executes the booking migrations; only the legacy public schema owns the platform directory.
DO $$
BEGIN
    IF current_schema() = 'public' THEN
        CREATE TABLE rush_accounts (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            username TEXT NOT NULL UNIQUE CHECK (char_length(username) BETWEEN 3 AND 40),
            password_hash TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE rush_tenants (
            id UUID PRIMARY KEY,
            owner_id UUID NOT NULL UNIQUE REFERENCES rush_accounts(id),
            slug TEXT NOT NULL UNIQUE,
            schema_name TEXT NOT NULL UNIQUE CHECK (schema_name ~ '^shop_[a-f0-9]{32}$'),
            display_name TEXT NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 70),
            area TEXT,
            hours TEXT,
            pickup_instructions TEXT,
            demo_fixture BOOLEAN NOT NULL DEFAULT FALSE,
            state TEXT NOT NULL CHECK (state IN ('PROVISIONING','ACTIVE','FAILED')),
            provisioning_started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
            created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
        );

        CREATE TABLE rush_web_sessions (
            token_hash TEXT PRIMARY KEY,
            account_id UUID NOT NULL REFERENCES rush_accounts(id) ON DELETE CASCADE,
            csrf_token TEXT NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL,
            revoked_at TIMESTAMPTZ
        );
        CREATE INDEX rush_web_sessions_expiry ON rush_web_sessions(expires_at);

        CREATE TABLE rush_native_bindings (
            tenant_id UUID PRIMARY KEY REFERENCES rush_tenants(id) ON DELETE CASCADE,
            token_hash TEXT NOT NULL UNIQUE,
            created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
        );
    END IF;
END $$;
