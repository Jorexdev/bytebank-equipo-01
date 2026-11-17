-- Migración: dinero de DOUBLE PRECISION a NUMERIC(19,2)
--
-- AVISO: ALTER COLUMN TYPE reescribe la tabla y toma ACCESS EXCLUSIVE LOCK.
--        Bloquea lecturas y escrituras en accounts y movements mientras dura.
--        Ejecutar en ventana de mantenimiento.
--
-- ROLLBACK: ver db/rollback/R4__balances_numeric_rollback.sql
--
-- LIMPIEZA: las tablas *_backup_pre_numeric y migration_* se eliminarán
--           en una migración posterior, tras validar en producción (p. ej. 4 semanas).
--
-- NOTA: la migración NO falla si hay descuadres; los registra en las tablas
--       migration_reconciliation_pre / _post para revisarlos después.

LOCK TABLE accounts, movements IN ACCESS EXCLUSIVE MODE;

-- 1. Backup de datos originales
CREATE TABLE accounts_backup_pre_numeric AS
    SELECT id, balance FROM accounts;
CREATE TABLE movements_backup_pre_numeric AS
    SELECT id, amount, balance_after FROM movements;

-- 2. Conciliación PREVIA: saldo de la cuenta vs. suma de movimientos (importe con signo).
--    Se compara redondeado a 2 decimales para ignorar el ruido de coma flotante;
--    lo que aparezca aquí es un descuadre real que ya existía antes de migrar.
CREATE TABLE migration_reconciliation_pre AS
SELECT a.id AS account_id,
       a.balance::numeric AS account_balance,
       COALESCE(SUM(m.amount::numeric), 0) AS movements_sum,
       a.balance::numeric - COALESCE(SUM(m.amount::numeric), 0) AS difference
FROM accounts a
LEFT JOIN movements m ON m.account_id = a.id
GROUP BY a.id, a.balance
HAVING round(a.balance::numeric, 2) <> round(COALESCE(SUM(m.amount::numeric), 0), 2);

-- 3. Rastro de los valores que cambiará el redondeo
CREATE TABLE migration_rounding_audit AS
SELECT 'accounts' AS table_name, id, 'balance' AS column_name,
       balance::numeric AS old_value, round(balance::numeric, 2) AS new_value
FROM accounts
WHERE balance::numeric <> round(balance::numeric, 2)
UNION ALL
SELECT 'movements', id, 'amount',
       amount::numeric, round(amount::numeric, 2)
FROM movements
WHERE amount::numeric <> round(amount::numeric, 2)
UNION ALL
SELECT 'movements', id, 'balance_after',
       balance_after::numeric, round(balance_after::numeric, 2)
FROM movements
WHERE balance_after::numeric <> round(balance_after::numeric, 2);

-- 4. Conversión de tipos
ALTER TABLE accounts
    ALTER COLUMN balance TYPE NUMERIC(19, 2) USING round(balance::numeric, 2);
ALTER TABLE accounts
    ALTER COLUMN balance SET DEFAULT 0.00;

ALTER TABLE movements
    ALTER COLUMN amount TYPE NUMERIC(19, 2) USING round(amount::numeric, 2),
    ALTER COLUMN balance_after TYPE NUMERIC(19, 2) USING round(balance_after::numeric, 2);

-- 5. Conciliación POSTERIOR (misma consulta, ya sobre NUMERIC).
--    Si aparecen cuentas aquí que no estaban en la previa, las introdujo el redondeo.
CREATE TABLE migration_reconciliation_post AS
SELECT a.id AS account_id,
       a.balance AS account_balance,
       COALESCE(SUM(m.amount), 0) AS movements_sum,
       a.balance - COALESCE(SUM(m.amount), 0) AS difference
FROM accounts a
LEFT JOIN movements m ON m.account_id = a.id
GROUP BY a.id, a.balance
HAVING a.balance <> COALESCE(SUM(m.amount), 0);

-- 6. Comprobación adicional: saldo de cuenta vs. balance_after del último movimiento
CREATE TABLE migration_last_balance_audit AS
SELECT a.id AS account_id,
       a.balance AS account_balance,
       m.balance_after AS last_movement_balance
FROM accounts a
LEFT JOIN LATERAL (
    SELECT balance_after FROM movements
    WHERE account_id = a.id
    ORDER BY created_at DESC, id DESC
    LIMIT 1
) m ON true
WHERE m.balance_after IS DISTINCT FROM a.balance;