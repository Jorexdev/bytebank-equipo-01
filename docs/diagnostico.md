# Diagnóstico de ByteBank


## Fallo 1: no funciona el login

### 1ª request
```bash
curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"email":"ana@bytebank.test","password":"Cliente1234!"}'
```

### 1ª response (error)
```bash
{"timestamp":"2026-11-10T03:29:49.451+00:00","path":"/api/auth/login","status":500,"error":"Internal Server Error","requestId":"829c5290-5"}
```

### Causa del 1er error 500
En el fichero [docker-compose.yml](/docker-compose.yml), la variable de entorno `AUTH_URL` apunta al puerto del microservicio de autenticación, pero ese puerto no es correcto, porque realmente dicho microservicio está expuesto en el puerto 8081 en vez del 8018.

### Solución

Modificar la línea 12 en [docker-compose.yml](/docker-compose.yml). Cambiar esto:
```yaml
AUTH_URL: http://ms-auth:8018
```

Por esto:
```yaml
AUTH_URL: http://ms-auth:8081
```

### 2ª request
```bash
curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"email":"ana@bytebank.test","password":"Cliente1234!"}'
```

### 2ª response (error)
```bash
{"timestamp":"2026-11-10T03:40:25.858+00:00","path":"/api/auth/login","status":500,"error":"Internal Server Error","requestId":"33fc155f-1"}
```

### 2ª request
```bash
curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"email":"ana@bytebank.test","password":"Cliente1234!"}'
```

### 2ª response (OK: con el token, el tipo de token, y su duración)
```bash
{"token":"eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiIyIiwiZW1haWwiOiJhbmFAYnl0ZWJhbmsudGVzdCIsInJvbGUiOiJDTElFTlRFIiwiaWF0IjoxNzk0MjgyNTMyLCJleHAiOjE3OTQzNjg5MzJ9.N_LBAAI6twOq_bmcDovFxdQGP-CJIEpRYp13AGGPo3jj6OfvyrFJv3zoVwapcd7E","tokenType":"Bearer","expiresInSeconds":86400}
```

## Fallo 2: avisos de movimientos que no existen (KAF-02)

### Qué pasaba
`MovementPublisher` llamaba a `kafka.send` dentro del `@Transactional` de `LedgerService` (y de `AccountService.deposit`), es decir, **antes del commit**. Si la transacción hacía rollback después de publicar, el evento ya había salido y `ms-notifications` avisaba al cliente de un movimiento que nunca llegó a existir.

### Por qué es grave
- El cliente recibe "Has enviado X €" de un dinero que no se ha movido.
- Si Kafka estaba caído, `send` bloqueaba hasta 60 s con la transacción de base de datos y la fila de la cuenta abiertas.
- Si el envío fallaba de forma asíncrona, nadie se enteraba y el movimiento quedaba sin aviso para siempre. El error de serialización JSON se tragaba con un log.

### Solución: patrón *transactional outbox*
- `MovementPublisher.publish` ya no habla con Kafka: guarda el evento en la tabla `outbox` (entidad `OutboxEvent`, repositorio `OutboxRepository`, migración `V4__outbox.sql`) **en la misma transacción que el movimiento**. Si hay rollback, desaparece también el evento.
- `OutboxRelay` (`@Scheduled`, cada 500 ms) lee un lote de hasta 100 eventos con `FOR UPDATE SKIP LOCKED`, los envía a Kafka, espera la confirmación y los borra. Si Kafka falla, hace rollback y reintenta, así que no se pierde ningún aviso.
- Se borra la fila al publicar en vez de marcarla, así la tabla casi siempre está vacía y no necesita limpieza ni índices extra.

### Duplicados
Si Kafka confirma el lote pero falla el borrado, el lote se reenvía. No hay avisos repetidos porque `ms-notifications` ya es idempotente por movimiento (KAF-03: comprobación previa y restricción `UNIQUE (user_id, movement_id)`).

### Tests
En `LedgerServiceIntegrationTest`: un movimiento con rollback no deja fila en `outbox` ni evento en Kafka, y un movimiento confirmado llega a Kafka y la `outbox` queda vacía.

### Orden por IBAN con varias réplicas
El contrato de `ms-notifications` promete que los eventos de una misma cuenta llegan en orden. Para cumplirlo con una o con varias réplicas de `ms-accounts`, `OutboxRelay.drain()` empieza con `pg_try_advisory_xact_lock(1)`: solo una réplica vacía la outbox a la vez. El bloqueo se libera al acabar la transacción, también si la réplica se cae, y la que no lo consigue sale sin esperar.

### Límite conocido
No hay reparto de carga entre réplicas: aunque haya varias, solo una envía a Kafka cada vez (hasta 100 eventos cada 500 ms, unos 200 por segundo). Es suficiente para este volumen. Si hiciera falta más, la alternativa es repartir por IBAN (un bloqueo por cubo `hash(iban) % N`), que mantiene el orden y permite el paralelismo. Está anotado con `shortcut:` en `OutboxRelay`.


## Fallo 3: no se puede pedir una tarjeta ni pagar con ella (TAR-01)

### Request
```bash
curl -s -X POST localhost:8080/api/cards -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"iban":"ES7639990001000000000001"}'
```

### Response (error)
Estado `501` con el código `NO_IMPLEMENTADO`. Lo mismo ocurría con `POST /api/cards/{id}/payments`.

### Causa del error 501
En [CardService.java](/ms-cards/src/main/java/dev/bytebank/cards/card/CardService.java), los métodos `issue` y `pay` eran un `// TODO` que lanzaba `NO_IMPLEMENTADO`. El contrato [ms-cards.md](/docs/contratos/ms-cards.md) pedía implementarlos y `AccountsClient` solo sabía consultar una cuenta, no cargar un pago.

### Solución

**Emitir tarjeta** (`CardService.issue`):
- Comprueba que el usuario es el titular de la cuenta; si no, o si no existe, `404 CUENTA_NO_ENCONTRADA` (el mismo error en los dos casos, para no revelar qué IBAN existen).
- Comprueba que la cuenta está `ACTIVA`; si no, `409 CUENTA_NO_OPERATIVA`.
- Solo se guardan los 4 últimos dígitos del número; el límite diario es el de `bytebank.cards.default-daily-limit` (600 €).

**Pagar con la tarjeta** (`CardService.pay` y el nuevo `PaymentReserver`):
- Rechaza con `TARJETA_BLOQUEADA` o `LIMITE_DIARIO_SUPERADO`, y guarda el intento rechazado para el historial.
- Si es válido, pide el cargo `PAGO_TARJETA` a ms-accounts (`AccountsClient.debit`, referencia `CARD-<id>`). Si ms-accounts responde con un error, el pago queda `RECHAZADO` con ese mismo código (`FONDOS_INSUFICIENTES`, `CUENTA_NO_OPERATIVA`).
- Responde `201` tanto si se aprueba como si se rechaza.

### Request de comprobación
```bash
curl -s -X POST localhost:8080/api/cards/1/payments -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"amount":42.50,"merchant":"Supermercado"}'
```

### Response (OK)
Estado `201` con `"status":"APROBADO"`, y un movimiento `PAGO_TARJETA` en la cuenta.

### Limitación conocida (pendiente)
Si ms-accounts hace *timeout* pero el cargo sí se llegó a aplicar, el pago queda `RECHAZADO` con `SERVICIO_NO_DISPONIBLE` (y la API devuelve `503`) con el dinero ya cobrado. Se arregla haciendo `reference` único e idempotente en ms-accounts (A-02) y reintentando el cargo desde ms-cards con la misma referencia. Queda como tarea aparte.