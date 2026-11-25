-- The roster of validated rules is shared by weighted orders in the demo environment.
-- Each cached document is the small allowlisted evaluator projection, without hosted credentials or identities.
CREATE TABLE IF NOT EXISTS public.flagsmith_slot_policy_cache (
    environment_hash CHAR(64) PRIMARY KEY,
    revision TIMESTAMPTZ NOT NULL,
    digest CHAR(64) NOT NULL,
    document JSONB NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL
);
