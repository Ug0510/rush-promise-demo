DO $$
DECLARE
    target_schema text;
    target_schemas text[] := ARRAY[current_schema()::text];
BEGIN
    IF current_schema() = 'public' THEN
        target_schemas := target_schemas || ARRAY(
            SELECT schema_name::text FROM public.rush_tenants
            WHERE state='ACTIVE' AND schema_name ~ '^shop_[a-f0-9]{32}$'
        );
    END IF;
    FOREACH target_schema IN ARRAY target_schemas LOOP
        IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=target_schema
                   AND table_name='guest_checkouts') THEN CONTINUE; END IF;
        EXECUTE format('CREATE TABLE %I.guest_checkouts (
            id UUID PRIMARY KEY, run_id UUID NOT NULL, token_hash TEXT NOT NULL CHECK (token_hash ~ ''^[a-f0-9]{64}$''),
            expires_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp() + interval ''24 hours'')', target_schema);
        EXECUTE format('CREATE INDEX guest_checkout_expiry_idx ON %I.guest_checkouts (expires_at)', target_schema);
        EXECUTE format('ALTER TABLE %I.booking_sessions DROP CONSTRAINT booking_native_kind,
            ADD CONSTRAINT booking_native_kind CHECK (native_identity_kind=''CALLER'' OR
                (native_identity_kind IN (''CALL'',''GUEST'') AND native_pickup_subject IS NOT NULL))', target_schema);
        EXECUTE format('ALTER TABLE %I.orders DROP CONSTRAINT order_native_kind,
            ADD CONSTRAINT order_native_kind CHECK (native_identity_kind=''CALLER'' OR
                (native_identity_kind IN (''CALL'',''GUEST'') AND native_pickup_subject IS NOT NULL AND
                 status NOT IN (''AWAITING_APPROVAL'',''APPROVAL_EXPIRED'',''REJECTED'')))', target_schema);
        EXECUTE format('CREATE UNIQUE INDEX orders_one_guest_pickup_idx
            ON %I.orders (run_id,native_pickup_subject) WHERE native_identity_kind=''GUEST''', target_schema);
    END LOOP;
END $$;
