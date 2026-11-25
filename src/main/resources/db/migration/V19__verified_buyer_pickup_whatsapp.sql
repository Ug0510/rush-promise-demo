DO $$
BEGIN
    IF current_schema() = 'public' THEN
        CREATE TABLE rush_buyer_pickup_links (
            call_hash TEXT PRIMARY KEY,
            tenant_id UUID NOT NULL REFERENCES rush_tenants(id) ON DELETE CASCADE,
            nonce UUID NOT NULL,
            challenge_hash TEXT UNIQUE,
            expires_at TIMESTAMPTZ NOT NULL,
            recipient TEXT,
            consented_at TIMESTAMPTZ,
            revoked_at TIMESTAMPTZ
        );

        CREATE TABLE rush_buyer_pickup_messages (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            call_hash TEXT NOT NULL UNIQUE REFERENCES rush_buyer_pickup_links(call_hash) ON DELETE CASCADE,
            run_id UUID NOT NULL,
            order_id BIGINT NOT NULL,
            status TEXT NOT NULL DEFAULT 'QUEUED',
            attempt_id UUID UNIQUE,
            provider_sid TEXT UNIQUE,
            error TEXT,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        CREATE INDEX rush_buyer_pickup_pending ON rush_buyer_pickup_messages(created_at) WHERE status='QUEUED';
    END IF;
END $$;
