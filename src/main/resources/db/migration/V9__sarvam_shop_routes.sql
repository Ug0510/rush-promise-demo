DO $$
BEGIN
    IF current_schema() = 'public' THEN
        CREATE TABLE sarvam_shop_routes (
            call_hash CHAR(64) PRIMARY KEY,
            tenant_id UUID NOT NULL REFERENCES rush_tenants (id),
            selected_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp()
        );
        CREATE INDEX sarvam_shop_routes_selected_idx ON sarvam_shop_routes (selected_at);
    END IF;
END $$;
