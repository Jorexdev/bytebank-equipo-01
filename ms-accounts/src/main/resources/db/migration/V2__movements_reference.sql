ALTER TABLE movements ADD COLUMN reference VARCHAR(64);

CREATE INDEX idx_movements_reference ON movements (reference);
