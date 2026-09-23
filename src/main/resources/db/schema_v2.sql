-- Schema version 2: coordinate basis provenance + display identity + client clocks.
ALTER TABLE sources ADD COLUMN coordinate_basis TEXT;
ALTER TABLE events  ADD COLUMN client_clock INTEGER NOT NULL DEFAULT 0;
ALTER TABLE artifacts ADD COLUMN display_key TEXT;
