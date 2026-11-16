-- El dinero se almacena en decimal exacto, no en coma flotante.
-- round(...::numeric, 2) limpia la basura que haya podido acumularse en DOUBLE PRECISION
-- (por ejemplo un saldo de 0.30000000000000004 pasa a ser 0.30).
ALTER TABLE accounts
    ALTER COLUMN balance TYPE NUMERIC(19, 2) USING round(balance::numeric, 2);

ALTER TABLE movements
    ALTER COLUMN balance_after TYPE NUMERIC(19, 2) USING round(balance_after::numeric, 2);
