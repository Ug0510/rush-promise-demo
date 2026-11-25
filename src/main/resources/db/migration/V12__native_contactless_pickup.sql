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
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema=target_schema AND table_name='orders' AND column_name='pickup_code') THEN
            CONTINUE;
        END IF;
        EXECUTE format('ALTER TABLE %I.booking_sessions ALTER COLUMN phone DROP NOT NULL,
            ADD COLUMN native_pickup_subject TEXT,
            ADD CONSTRAINT booking_contact_defined CHECK (
                (phone IS NOT NULL AND native_pickup_subject IS NULL) OR
                (phone IS NULL AND native_pickup_subject IS NOT NULL AND native_pickup_subject ~ ''^[a-f0-9]{64}$''))', target_schema);
        EXECUTE format('ALTER TABLE %I.quotes ALTER COLUMN phone DROP NOT NULL', target_schema);
        EXECUTE format('ALTER TABLE %I.orders ALTER COLUMN phone DROP NOT NULL, ADD COLUMN native_pickup_subject TEXT,
            ADD COLUMN pickup_code TEXT,
            ADD CONSTRAINT order_contact_defined CHECK (
                (phone IS NOT NULL AND pickup_code IS NULL AND native_pickup_subject IS NULL) OR
                (phone IS NULL AND pickup_code IS NOT NULL AND pickup_code ~ ''^[1-9][0-9]{7}$''
                  AND native_pickup_subject IS NOT NULL AND native_pickup_subject ~ ''^[a-f0-9]{64}$''))', target_schema);
        EXECUTE format('CREATE UNIQUE INDEX orders_native_pickup_code_idx ON %I.orders (pickup_code) WHERE pickup_code IS NOT NULL', target_schema);
        EXECUTE format('CREATE UNIQUE INDEX orders_pending_native_subject_idx ON %I.orders (run_id,native_pickup_subject)
            WHERE native_pickup_subject IS NOT NULL AND status=''AWAITING_APPROVAL''', target_schema);
    END LOOP;
END $$;
