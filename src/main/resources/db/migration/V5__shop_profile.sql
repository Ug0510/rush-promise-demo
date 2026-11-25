ALTER TABLE shop_state
    ADD COLUMN display_name TEXT NOT NULL DEFAULT 'Sharma Sweets' CHECK (char_length(display_name) BETWEEN 1 AND 70),
    ADD COLUMN profile_updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
