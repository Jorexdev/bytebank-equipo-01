-- Rollback manual de V4 (DOUBLE PRECISION -> NUMERIC(19,2)).
-- Ejecutar solo si V4 ya se aplicó.
--
-- AVISOS
--   * Ejecutar en ventana de mantenimiento: ALTER COLUMN TYPE reescribe las tablas
--     y toma ACCESS EXCLUSIVE LOCK (bloquea lecturas y escrituras).
--   * Los saldos e importes de las filas que ya existían en V4 se SOBRESCRIBEN con los
--     valores del backup. Cualquier cambio en ellas posterior a V4 se pierde. Ejecutar
--     sin tráfico de escritura y solo si se acepta esa pérdida.
--   * Las filas creadas después de V4 no están en el backup: se quedan con el valor
--     convertido a double precision.
--   * El rollback NO elimina las tablas *_backup_pre_numeric ni migration_*.
--
-- DESPUÉS DEL ROLLBACK
--   1. Borrar la fila de V4 de flyway_schema_history (o usar `flyway repair`)
--      y retirar V4 del código antes del siguiente despliegue.
--   2. Antes de volver a aplicar V4, eliminar o renombrar las tablas que creó,
--      porque su CREATE TABLE ... AS fallaría si ya existen:
--        DROP TABLE accounts_backup_pre_numeric, movements_backup_pre_numeric,
--                   migration_reconciliation_pre, migration_rounding_audit,
--                   migration_reconciliation_post, migration_last_balance_audit;
--   3. V4 hizo `ALTER COLUMN balance SET DEFAULT 0.00` en accounts. Este rollback no
--      lo revierte. Si antes de V4 la columna no tenía default, ejecutar:
--        ALTER TABLE accounts ALTER COLUMN balance DROP DEFAULT;

BEGIN;

LOCK TABLE accounts, movements IN ACCESS EXCLUSIVE MODE;

-- Abortar si faltan los backups, antes de tocar nada.
DO $$
BEGIN
    IF to_regclass('accounts_backup_pre_numeric') IS NULL
       OR to_regclass('movements_backup_pre_numeric') IS NULL THEN
        RAISE EXCEPTION 'Faltan las tablas de backup pre-numeric: no se puede hacer rollback';
    END IF;
END $$;

-- 1. Volver a los tipos originales
ALTER TABLE accounts
    ALTER COLUMN balance TYPE DOUBLE PRECISION USING balance::double precision;

ALTER TABLE movements
    ALTER COLUMN amount TYPE DOUBLE PRECISION USING amount::double precision,
    ALTER COLUMN balance_after TYPE DOUBLE PRECISION USING balance_after::double precision;

-- 2. Restaurar los valores originales (sin redondear) desde los backups
UPDATE accounts a
   SET balance = b.balance
  FROM accounts_backup_pre_numeric b
 WHERE a.id = b.id;

UPDATE movements m
   SET amount = b.amount,
       balance_after = b.balance_after
  FROM movements_backup_pre_numeric b
 WHERE m.id = b.id;

COMMIT;