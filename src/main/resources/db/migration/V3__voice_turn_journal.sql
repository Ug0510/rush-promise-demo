CREATE TABLE voice_sessions (
    run_id UUID NOT NULL,
    session_id TEXT NOT NULL,
    contact_fingerprint TEXT,
    state JSONB NOT NULL DEFAULT '{}'::jsonb,
    max_sequence INTEGER NOT NULL DEFAULT 0 CHECK (max_sequence >= 0),
    ended BOOLEAN NOT NULL DEFAULT FALSE,
    touched_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, session_id)
);

CREATE TABLE voice_turns (
    run_id UUID NOT NULL,
    session_id TEXT NOT NULL,
    turn_id TEXT NOT NULL,
    sequence INTEGER NOT NULL CHECK (sequence > 0),
    status TEXT NOT NULL CHECK (status IN ('PROCESSING','ACCEPTED','UNCERTAIN','DONE','FAILED','DROPPED')),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    accepted_quote_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, session_id, turn_id),
    UNIQUE (run_id, session_id, sequence),
    FOREIGN KEY (run_id, session_id) REFERENCES voice_sessions(run_id, session_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX voice_one_pending_acceptance ON voice_turns (run_id, session_id)
    WHERE status IN ('ACCEPTED','UNCERTAIN');
CREATE INDEX voice_recovery_due ON voice_turns (lease_until) WHERE status IN ('PROCESSING','ACCEPTED','UNCERTAIN');
CREATE INDEX voice_session_retention ON voice_sessions (run_id, touched_at);
