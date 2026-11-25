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
        IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=target_schema
                   AND table_name='orders' AND column_name='native_identity_kind') THEN CONTINUE; END IF;
        EXECUTE format('ALTER TABLE %I.booking_sessions ADD COLUMN native_identity_kind TEXT NOT NULL DEFAULT ''CALLER'',
            ADD CONSTRAINT booking_native_kind CHECK (native_identity_kind=''CALLER'' OR
                (native_identity_kind=''CALL'' AND native_pickup_subject IS NOT NULL))', target_schema);
        EXECUTE format('ALTER TABLE %I.orders ADD COLUMN native_identity_kind TEXT NOT NULL DEFAULT ''CALLER'',
            ADD CONSTRAINT order_native_kind CHECK (native_identity_kind=''CALLER'' OR
                 (native_identity_kind=''CALL'' AND native_pickup_subject IS NOT NULL AND
                  status NOT IN (''AWAITING_APPROVAL'',''APPROVAL_EXPIRED'',''REJECTED'')))', target_schema);
        EXECUTE format('CREATE UNIQUE INDEX orders_one_native_call_pickup_idx
            ON %I.orders (run_id,native_pickup_subject) WHERE native_identity_kind=''CALL''', target_schema);
    END LOOP;
END $$;
