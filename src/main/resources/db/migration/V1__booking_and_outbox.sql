CREATE TABLE shop_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    run_id UUID NOT NULL,
    demo_now TIMESTAMPTZ,
    demo_started_at TIMESTAMPTZ
);

CREATE TABLE items (
    sku TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    price_per_kg_paise INTEGER NOT NULL CHECK (price_per_kg_paise > 0)
);

CREATE TABLE batches (
    id TEXT PRIMARY KEY,
    sku TEXT NOT NULL REFERENCES items(sku),
    ready_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('READY', 'PLANNED')),
    phone_grams_total INTEGER NOT NULL CHECK (phone_grams_total >= 0),
    phone_grams_free INTEGER NOT NULL CHECK (phone_grams_free BETWEEN 0 AND phone_grams_total),
    counter_grams_counted INTEGER NOT NULL CHECK (counter_grams_counted >= 0),
    counter_counted_at TIMESTAMPTZ,
    blocked BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE pickup_slots (
    pickup_at TIMESTAMPTZ PRIMARY KEY,
    capacity INTEGER NOT NULL CHECK (capacity >= 0),
    used INTEGER NOT NULL DEFAULT 0 CHECK (used BETWEEN 0 AND capacity)
);

CREATE TABLE booking_sessions (
    id TEXT PRIMARY KEY,
    run_id UUID NOT NULL,
    phone TEXT NOT NULL,
    latest_quote_id UUID
);

CREATE TABLE quotes (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    session_id TEXT NOT NULL REFERENCES booking_sessions(id),
    customer_name TEXT NOT NULL,
    phone TEXT NOT NULL,
    sku TEXT NOT NULL REFERENCES items(sku),
    item_name TEXT NOT NULL,
    grams INTEGER NOT NULL CHECK (grams > 0),
    price_paise INTEGER NOT NULL CHECK (price_paise > 0),
    pickup_at TIMESTAMPTZ NOT NULL REFERENCES pickup_slots(pickup_at),
    batch_id TEXT NOT NULL REFERENCES batches(id),
    requires_approval BOOLEAN NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    policy JSONB NOT NULL
);

CREATE TABLE orders (
    id BIGINT PRIMARY KEY,
    run_id UUID NOT NULL,
    accepted_quote_id UUID NOT NULL UNIQUE REFERENCES quotes(id),
    accepted_turn_id TEXT NOT NULL,
    customer_name TEXT NOT NULL,
    phone TEXT NOT NULL,
    sku TEXT NOT NULL REFERENCES items(sku),
    item_name TEXT NOT NULL,
    grams INTEGER NOT NULL CHECK (grams > 0),
    price_paise INTEGER NOT NULL CHECK (price_paise > 0),
    pickup_at TIMESTAMPTZ NOT NULL REFERENCES pickup_slots(pickup_at),
    batch_id TEXT NOT NULL REFERENCES batches(id),
    status TEXT NOT NULL CHECK (status IN ('CONFIRMED', 'AWAITING_APPROVAL', 'APPROVAL_EXPIRED', 'REJECTED', 'PACKED', 'SHORT', 'CANCELLED')),
    approval_expires_at TIMESTAMPTZ,
    policy JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    packed_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    returned_at TIMESTAMPTZ,
    return_to_tray BOOLEAN NOT NULL DEFAULT FALSE,
    check_stock BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX orders_one_pending_per_phone ON orders (run_id, phone) WHERE status = 'AWAITING_APPROVAL';
CREATE INDEX orders_approval_expiry ON orders (approval_expires_at) WHERE status = 'AWAITING_APPROVAL';

CREATE TABLE booking_keys (
    run_id UUID NOT NULL,
    key TEXT NOT NULL,
    quote_id UUID NOT NULL,
    request_session TEXT NOT NULL,
    request_turn TEXT NOT NULL,
    status INTEGER NOT NULL,
    outcome JSONB NOT NULL,
    PRIMARY KEY (run_id, key)
);

CREATE TABLE order_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id UUID NOT NULL,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    kind TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE outbox_messages (
    id UUID PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    run_id UUID NOT NULL,
    kind TEXT NOT NULL,
    status TEXT NOT NULL,
    recipient TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, order_id, kind)
);

CREATE SEQUENCE order_number_seq START WITH 4821;

INSERT INTO shop_state (id, run_id) VALUES (1, gen_random_uuid());

CREATE TABLE flagsmith_policy_cache (
    environment_hash TEXT PRIMARY KEY,
    rush_mode BOOLEAN NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL
);

CREATE VIEW shop_clock AS
SELECT run_id, CASE
    WHEN demo_now IS NULL THEN statement_timestamp()
    WHEN demo_started_at IS NULL THEN demo_now
    ELSE demo_now + (statement_timestamp() - demo_started_at)
END AS now
FROM shop_state WHERE id = 1;
