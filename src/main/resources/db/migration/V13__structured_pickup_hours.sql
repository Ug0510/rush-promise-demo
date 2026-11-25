DO $$
DECLARE
    target_schema text;
    target_schemas text[] := ARRAY[current_schema()::text];
    merchant record;
    chosen text[];
BEGIN
    IF current_schema() = 'public' THEN
        target_schemas := target_schemas || ARRAY(
            SELECT schema_name::text FROM public.rush_tenants
            WHERE state='ACTIVE' AND schema_name ~ '^shop_[a-f0-9]{32}$'
        );
    END IF;
    FOREACH target_schema IN ARRAY target_schemas LOOP
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema=target_schema AND table_name='shop_state' AND column_name='pickup_all_day') THEN
            CONTINUE;
        END IF;
        EXECUTE format('ALTER TABLE %I.shop_state ADD COLUMN pickup_all_day BOOLEAN,
            ADD COLUMN pickup_opens_at TIME, ADD COLUMN pickup_closes_at TIME,
            ADD CONSTRAINT shop_pickup_hours_valid CHECK (
                ((pickup_all_day IS NULL OR pickup_all_day IS TRUE) AND pickup_opens_at IS NULL AND pickup_closes_at IS NULL)
                OR (pickup_all_day IS FALSE AND pickup_opens_at IS NOT NULL AND pickup_closes_at IS NOT NULL
                    AND pickup_opens_at <= pickup_closes_at
                    AND EXTRACT(MINUTE FROM pickup_opens_at) IN (0,30) AND EXTRACT(MINUTE FROM pickup_closes_at) IN (0,30)
                    AND EXTRACT(SECOND FROM pickup_opens_at)=0 AND EXTRACT(SECOND FROM pickup_closes_at)=0))', target_schema);
        IF current_schema() = 'public' AND target_schema <> 'public' THEN
            SELECT slug,demo_fixture,hours INTO merchant FROM public.rush_tenants WHERE schema_name=target_schema;
            chosen := regexp_match(trim(merchant.hours), '^((?:[01][0-9]|2[0-3]):(?:00|30))\s*[-–—]\s*((?:[01][0-9]|2[0-3]):(?:00|30))$');
            IF chosen IS NOT NULL AND chosen[1] <= chosen[2] THEN
                EXECUTE format('UPDATE %I.shop_state SET pickup_all_day=FALSE,pickup_opens_at=$1::time,pickup_closes_at=$2::time WHERE id=1', target_schema)
                    USING chosen[1],chosen[2];
            ELSIF merchant.demo_fixture AND merchant.slug IN ('deepak-mithai-bhandar','virajs-bakery') THEN
                EXECUTE format('UPDATE %I.shop_state SET pickup_all_day=FALSE,pickup_opens_at=TIME ''09:00'',pickup_closes_at=TIME ''21:00'' WHERE id=1', target_schema);
            END IF;
        END IF;
    END LOOP;
END $$;
