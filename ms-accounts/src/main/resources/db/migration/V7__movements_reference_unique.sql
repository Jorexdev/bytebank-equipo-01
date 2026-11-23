-- Una referencia identifica una operación: el mismo (reference, type) no puede aplicarse dos veces.
-- El tipo forma parte de la clave porque una transferencia usa TRF-<id> en el cargo y en el abono.
-- Los movimientos sin referencia (ingresos) tienen NULL y no entran en la restricción.
-- Si esta migración falla por duplicados, NO los borres: son apuntes contables, revísalos a mano.
DROP INDEX idx_movements_reference;
CREATE UNIQUE INDEX uq_movements_reference_type ON movements (reference, type);
