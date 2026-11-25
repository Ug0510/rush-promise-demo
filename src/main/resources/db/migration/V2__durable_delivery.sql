ALTER TABLE outbox_messages
    ADD COLUMN attempt_count integer NOT NULL DEFAULT 0,
    ADD COLUMN retry_count integer NOT NULL DEFAULT 0,
    ADD COLUMN retry_started_at timestamptz,
    ADD COLUMN lease_expires_at timestamptz,
    ADD COLUMN lease_token uuid,
    ADD COLUMN attempt_id uuid,
    ADD COLUMN provider_sid text,
    ADD COLUMN last_error text,
    ADD COLUMN sent_at timestamptz,
    ADD COLUMN delivered_at timestamptz;

CREATE TABLE notification_attempts (
    id uuid PRIMARY KEY,
    message_id uuid NOT NULL REFERENCES outbox_messages(id) ON DELETE CASCADE,
    run_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    status text NOT NULL,
    provider_sid text UNIQUE,
    last_error text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (message_id, attempt_number)
);

-- Only one order is eligible for real trial delivery until an operator clears this database.
-- No foreign key here: resets must not silently release approval for another order.
CREATE TABLE notification_live_grant (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    run_id uuid NOT NULL,
    order_id bigint NOT NULL,
    approval_ref text NOT NULL,
    granted_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX outbox_dispatch_due_idx ON outbox_messages (next_attempt_at, created_at, id)
    WHERE status IN ('QUEUED', 'FAILED_RETRYABLE');
CREATE INDEX outbox_order_pending_idx ON outbox_messages (run_id, order_id, created_at, id)
    WHERE status IN ('QUEUED', 'CLAIMED', 'SENDING', 'FAILED_RETRYABLE', 'UNKNOWN', 'NEEDS_REVIEW');
CREATE INDEX outbox_leases_idx ON outbox_messages (lease_expires_at)
    WHERE status IN ('CLAIMED', 'SENDING');
