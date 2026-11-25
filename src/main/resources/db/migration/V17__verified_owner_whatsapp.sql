DO $$
BEGIN
    IF current_schema() = 'public' THEN
        CREATE TABLE rush_owner_whatsapp (
            tenant_id UUID PRIMARY KEY REFERENCES rush_tenants(id) ON DELETE CASCADE,
            link_id UUID UNIQUE,
            recipient TEXT UNIQUE,
            last_inbound_at TIMESTAMPTZ,
            last_pin_at TIMESTAMPTZ,
            link_code_hash TEXT UNIQUE,
            link_code_expires_at TIMESTAMPTZ
        );

        CREATE TABLE rush_owner_whatsapp_inbound (
            provider_sid TEXT PRIMARY KEY,
            received_at TIMESTAMPTZ NOT NULL DEFAULT now()
        );

        CREATE TABLE rush_owner_whatsapp_messages (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            tenant_id UUID NOT NULL REFERENCES rush_tenants(id) ON DELETE CASCADE,
            run_id UUID NOT NULL,
            order_id BIGINT NOT NULL,
            link_id UUID NOT NULL,
            body TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'QUEUED',
            attempt_id UUID UNIQUE,
            provider_sid TEXT UNIQUE,
            error TEXT,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            UNIQUE (tenant_id, run_id, order_id)
        );
        CREATE INDEX rush_owner_whatsapp_pending ON rush_owner_whatsapp_messages (created_at)
            WHERE status='QUEUED';
        CREATE INDEX rush_owner_whatsapp_latest ON rush_owner_whatsapp_messages (tenant_id, created_at DESC);
    END IF;
END $$;
