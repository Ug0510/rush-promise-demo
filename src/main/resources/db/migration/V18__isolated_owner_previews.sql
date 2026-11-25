DO $$
BEGIN
    IF current_schema() = 'public' THEN
        ALTER TABLE rush_accounts ADD COLUMN preview_key_hash TEXT UNIQUE,
            ADD COLUMN preview_expires_at TIMESTAMPTZ;
        CREATE INDEX rush_accounts_preview_expiry ON rush_accounts(preview_expires_at) WHERE preview_expires_at IS NOT NULL;
    END IF;
END $$;
