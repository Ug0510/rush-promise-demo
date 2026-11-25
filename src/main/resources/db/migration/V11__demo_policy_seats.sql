CREATE TABLE demo_policy_seats (
    pickup_at TIMESTAMPTZ NOT NULL REFERENCES pickup_slots(pickup_at) ON DELETE CASCADE,
    seat_number SMALLINT NOT NULL CHECK (seat_number BETWEEN 1 AND 4),
    batch_id TEXT NOT NULL REFERENCES batches(id),
    order_id BIGINT NOT NULL UNIQUE REFERENCES orders(id) ON DELETE CASCADE CHECK (order_id < 0),
    grams INTEGER NOT NULL CHECK (grams = 500),
    PRIMARY KEY (pickup_at, seat_number)
);
