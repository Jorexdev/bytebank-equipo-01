# Arquitectura

```
                         +-------------+
   cliente  ---------->  | ms-gateway  |  :8080   (solo /api/**)
                         +------+------+
                                |
      +-----------+-------------+-------------+-------------+
      |           |             |             |             |
 +----v----+ +----v------+ +----v-------+ +---v------+ +----v-----+
 | ms-auth | |ms-accounts| |ms-transfers| | ms-cards | | ms-fraud |
 |  :8081  | |   :8082   | |   :8083    | |  :8084   | |  :8085   |
 +---------+ +-----^-----+ +-----+------+ +----+-----+ +----------+
                   |             |             |
                   +-------------+-------------+
                     llamadas internas /internal/**
```

Cada servicio tiene su propia base de datos (`auth_db`, `accounts_db`, `transfers_db`, `cards_db`, `fraud_db`),
todas en la misma instancia de PostgreSQL. Ningún servicio lee la base de datos de otro.

## Seguridad

- **Clientes**: ms-auth emite un JWT HS256 con el id del usuario (`sub`), su `email` y su `role`
  (`CLIENTE` o `GESTOR`). El resto de servicios valida el token en cada petición (módulo `common`).
- **Entre servicios**: las rutas `/internal/**` no pasan por el gateway y exigen la cabecera `X-Internal-Key`
  con la clave compartida (`INTERNAL_KEY`).

## Módulo common

Código compartido por los servicios de dominio:

- `security`: filtro JWT, filtro de clave interna, `CurrentUser` para saber quién llama.
- `error`: `ApiException` y el formato único de error.
- `http`: `InternalClients` para llamar a otros servicios con la clave interna.
- `iban`: validación y generación de IBAN (ByteBank es la entidad 3999).

## Formato de error

Todos los servicios devuelven los errores así:

```json
{
  "timestamp": "2026-06-10T09:30:00Z",
  "status": 409,
  "error": "Conflict",
  "code": "FONDOS_INSUFICIENTES",
  "message": "Saldo insuficiente",
  "path": "/internal/accounts/debit"
}
```

`code` es estable y es lo que deben mirar los clientes y los demás servicios; `message` es para personas.

## Flujo de una transferencia

1. El cliente hace `POST /api/transfers` con origen, destino, importe y concepto.
2. ms-transfers valida los datos y pregunta a ms-fraud (`POST /internal/fraud/check`) **antes** de mover dinero.
   ms-fraud aplica todas las reglas, incluido el importe alto: más de 5.000 € (configurable en
   `bytebank.fraud.review-threshold`) devuelve `REVISAR`. ms-transfers no tiene umbrales propios, solo obedece
   el veredicto.
3. La transferencia se guarda primero, sin mover dinero, y según el veredicto:
   - `APROBADA`: se sigue con el paso 4.
   - `REVISAR`: queda `PENDIENTE_REVISION`, esperando a un gestor.
   - `BLOQUEADA`: queda `RECHAZADA` con motivo `FRAUDE`.
4. Si se ejecuta ya: carga el importe en la cuenta de origen (`POST /internal/accounts/debit` en ms-accounts) y,
   si el destino es de ByteBank, lo abona (`POST /internal/accounts/credit`). Queda `COMPLETADA` o `RECHAZADA`
   con el código del error.
5. Las `PENDIENTE_REVISION` las resuelve un gestor:
   - `GET /api/transfers/pending`: la cola de transferencias pendientes.
   - `POST /api/transfers/{id}/approve`: ejecuta los mismos movimientos del paso 4.
   - `POST /api/transfers/{id}/reject`: cierra la transferencia como `RECHAZADA` sin tocar el ledger
     (motivo `RECHAZADA_POR_GESTOR` si no se indica otro).
   Cada transferencia se resuelve una sola vez; un segundo intento responde `409 TRANSFERENCIA_YA_RESUELTA`
   y la fila se bloquea durante la resolución para que dos gestores no muevan el dinero dos veces.

Las transferencias a otras entidades solo cargan en origen: la liquidación con el otro banco queda fuera de este sistema.

## Roles

| Rol | Puede |
|---|---|
| CLIENTE | Abrir cuentas, ver las suyas, hacer transferencias desde ellas, gestionar sus tarjetas |
| GESTOR | Todo lo de consulta, bloquear y desbloquear cuentas, hacer ingresos en ventanilla, ver las validaciones antifraude, aprobar o rechazar transferencias pendientes |
