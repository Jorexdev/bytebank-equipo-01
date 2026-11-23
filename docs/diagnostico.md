# Diagnóstico de ByteBank

## Estado actual (23 nov 2026)

Las secciones "Fallo 1", "Fallo 2", "Fallo 3" y "Fallo 4" de más abajo son el diario histórico de cómo se resolvieron
cuatro problemas. El estado contrastado con el código hoy es el siguiente. Referencia: `main` (`330be1c`, PR #24).

### Resuelto

| Tema | Evidencia |
|---|---|
| Login: `AUTH_URL` apuntaba al puerto 8018 | `docker-compose.yml:8` → `AUTH_URL: http://ms-auth:8081` |
| Avisos de movimientos que no existen (KAF-02) | `MovementPublisher.java:27-35` escribe en outbox; `OutboxRelay.java:23-33` publica; `V5__outbox.sql` |
| Antifraude no invocado en transferencias | `TransferService.java:62-73` consulta a ms-fraud antes de mover dinero |
| Emitir y pagar con tarjeta (TAR-01) | `CardService.java:37-48` y `:68-86`; `PaymentReserver.java` |
| Consultar avisos (KAF-01) | `NotificationController.java:22-28` (`GET /api/notifications`) |
| Importes monetarios con `double` | `V4__balances_numeric.sql` los convierte a `NUMERIC(19,2)`; `LedgerDtos.java:21` usa `BigDecimal` |
| CI que nunca fallaba por build roto | `.github/workflows/ci.yml:21-22` → `mvn -B verify` |
| Cuentas con varios titulares | `V6__account_holders.sql`; las respuestas incluyen `holderIds` |
| Importe de transferencias sin validar en ms-transfers (BUG-01) | `TransferDtos.java:20` con `@DecimalMin("0.01")` y `@Digits(17,2)`; `@Valid` en `TransferController.java:33` |
| `/api/fraud/checks` accesible por clientes | `FraudController.java:35` con `@PreAuthorize("hasRole('GESTOR')")`; `/internal/fraud/check` no se tocó |
| Datos demo sin titular tras CAM-01: `ana` y `luis` veían `GET /api/accounts` vacío con una BD nueva | `DemoSeeder.java:58` guarda el `AccountHolder`; comprobado con `docker compose down -v` y `up` (ana ve 2 cuentas, luis 1) |
| Perfil `demo` activo por defecto | `docker-compose.yml:16` usa `${SPRING_PROFILES_ACTIVE:?...}` y `.env.example:8` lo deja vacío; los seeders siguen con `@Profile("demo")` |

### En revisión (PR abiertas, todavía no en `main`)

Se mergean en este orden, porque cada una depende de la anterior.

| PR | Qué arregla | Depende de |
|---|---|---|
| #26 | `V2` de ms-notifications falla en una BD con avisos duplicados: borra los duplicados antes de crear la restricción única | Nada |
| #27 | ms-accounts ignora una referencia repetida (único `(reference, type)`, V7): un reintento ya no mueve el dinero dos veces; responde `409 REFERENCIA_REUTILIZADA` si la referencia se usó con otra cuenta o importe | Nada |
| #28 | ms-transfers repite hasta 3 veces el cargo y el abono ante un timeout, con la misma referencia | #27 |
| #29 | Devuelve el cargo si falla el abono; `approve` deja de llamar por HTTP dentro de una transacción (estado `EN_PROCESO` + `claimPending`) y comprueba que el destino sigue activo | #27 y #28 |

### Pendientes y riesgos

#### Abiertos (de más a menos grave)

Los números 1 a 5 dejan dinero en un estado dudoso si ocurren; los demás son operativos. Todo está contrastado con el código,
salvo lo que se indica como no reproducido.

| # | Riesgo | Qué pasa | Cómo se cierra |
|---|---|---|---|
| 1 | Abono sin respuesta tras los 3 reintentos | El origen queda cargado y no se devuelve, porque no se sabe si el abono se aplicó. La transferencia queda `RECHAZADA` con `REEMBOLSO_FALLIDO` y un `log.error`. Antes de la #29 el cargo tampoco se devolvía en ningún caso | Conciliar a mano con el `log.error`; a medio plazo, una tarea que consulte el movimiento por referencia en ms-accounts y complete o devuelva |
| 2 | Transferencia guardada como `COMPLETADA` antes de mover el dinero | El constructor de `Transfer` la crea `COMPLETADA` y `TransferService.create` la guarda antes de `execute` (`TransferService.java:77`). Si el proceso muere o falla algo que no sea `ApiException` entre el guardado y el cargo, queda `COMPLETADA` sin cargo. No reproducido | Crearla `EN_PROCESO` (el estado que añade la #29) y marcarla `COMPLETADA` al terminar |
| 3 | Cargo sin respuesta tras los 3 reintentos | La transferencia queda `RECHAZADA` con `SERVICIO_NO_DISPONIBLE` aunque el cargo pudiera haberse aplicado. Mucho menos probable con la #27 y la #28, pero no imposible | Misma tarea de conciliación que el punto 1 |
| 4 | `EN_PROCESO` huérfana (#29) | Si ms-transfers se cae entre pasar a `EN_PROCESO` y guardar el resultado, la transferencia se queda así y nadie puede aprobarla ni rechazarla. Antes lo deshacía el rollback | `transfers` no guarda cuándo pasó a `EN_PROCESO` (solo `created_at`, y una pendiente puede ser antigua), así que no se detectan de forma fiable. Añadir una columna de fecha de última actualización y una consulta o tarea que avise |
| 5 | ms-cards no reintenta su cargo | Un timeout deja el pago `RECHAZADO` con el dinero ya cobrado (ver "Limitación conocida" del Fallo 3). Con la #27 ya se puede reintentar con `CARD-<id>` | PR aparte en ms-cards, igual que la #28 |
| 6 | Mismo `(reference, type)` en dos cuentas a la vez | Las dos peticiones no se serializan (cada una bloquea su cuenta); la restricción única rechaza la segunda con un error de BD en vez de un 409. Es un uso incorrecto de la API, no un reintento | Capturar `DataIntegrityViolationException` en `LedgerService` y devolver `REFERENCIA_REUTILIZADA` |
| 7 | Devolución registrada como `TRANSFERENCIA_RECIBIDA` | No existe un tipo de movimiento de reembolso; el concepto dice "Devolución de la transferencia N", pero el tipo es el de un abono recibido | Añadir un tipo `DEVOLUCION` a `MovementType` y usarlo en `refundOrigin` |
| 8 | Migraciones sin test automático | La V2 de la #26 se verificó a mano con `psql`. Editar una migración ya aplicada cambia su checksum: quien tenga V2 aplicada debe hacer `docker compose down -v` | Test de migración con Testcontainers; no editar migraciones aplicadas en entornos compartidos (usar una nueva) |
| 9 | #27, #28 y #29 sin probar juntas | Los tests de ms-transfers usan mocks de ms-accounts, salvo el de concurrencia. La #27 se probó con `docker compose` por separado | Probar las tres con `docker compose` cuando estén en `main`: transferencia con abono a una cuenta bloqueada y con ms-accounts parado |
| 10 | Avisos limitados a un relay | Solo una réplica de ms-accounts vacía la outbox a la vez (unos 200 eventos por segundo). Anotado con `shortcut:` en `OutboxRelay` | Repartir por IBAN (un bloqueo por cubo `hash(iban) % N`) si el volumen lo pide |
| 11 | `main` sin protección | Nada obliga a pasar por PR ni a que la revise otra persona; el CI no es obligatorio | Pedir al admin del repo: PR con 1 aprobación, check `mvn -B verify` obligatorio y sin push directo |

#### Ya resueltos (de más a menos grave)

Riesgos que el equipo ya cerró en PR mergeadas. La columna "PR" es la que los cerró; el código que lo demuestra de los más
recientes está en la tabla "Resuelto" de arriba. Esta lista sale del historial de PR y de los mensajes de commit; no se ha
vuelto a ejecutar cada caso.

| Riesgo | Qué pasaba | Cómo se resolvió | PR |
|---|---|---|---|
| Acceso a cuentas ajenas | Un cliente podía consultar y operar cuentas que no eran suyas | Solo se accede a cuentas propias; el gestor puede operar sobre cualquiera. Tests de acceso por rol y 404 | #7 (SEG-01) |
| Secreto JWT en el código y contraseñas en logs | El secreto estaba escrito en el repositorio y las contraseñas se registraban | Se quita el secreto JWT escrito en el código, se centraliza la clave, no se loguean contraseñas y la documentación no lleva secretos | #15 (SEG-02) |
| Transferencias sin comprobar la cuenta de origen ni el antifraude | Se podía transferir desde una cuenta ajena y sin consultar a ms-fraud | Se valida que el origen pertenece al usuario y se consulta el antifraude antes de mover dinero | #6 (BUG-01) |
| Saldo negativo por operaciones simultáneas | Dos cargos a la vez podían dejar el saldo por debajo de cero | Bloqueo pesimista en cargos y abonos, con `lock_timeout` y error 409 si no se consigue el bloqueo | #8 (CON-01) |
| Importes en `double` | `0,10 + 0,20` daba `0,30000000000000004` | Saldos y movimientos pasan a `NUMERIC(19,2)` / `BigDecimal` (`V4__balances_numeric.sql`) | #11 (BUG-05) |
| Avisos de movimientos que no existen | Se publicaba en Kafka dentro de la transacción: un rollback dejaba un aviso falso | Patrón *outbox* con relay y bloqueo advisory (Fallo 2). Se revirtió una vez (#17) y se reaplicó corregido | #12, #17, #18 (KAF-02) |
| Un abono a una cuenta inactiva cargaba el origen | Se debitaba el importe aunque el destino no estuviera activa | Se comprueba que el destino esté activo antes de cargar | #19 (BUG-06) |
| Transferencias grandes sin revisión y aprobaciones simultáneas | Una transferencia grande se ejecutaba sin que nadie la revisara; dos gestores podían aprobarla a la vez | Las grandes quedan pendientes hasta que un gestor las revise, con test de dos aprobaciones simultáneas que mueven el dinero una sola vez | #20 (CAM-02) |
| Antifraude incompleto | Sin regla de frecuencia, sin revisión ni bloqueo en ms-transfers | Regla `FRECUENCIA_ALTA` con test de concurrencia (umbral y ventana configurables); `REVISAR` deja la transferencia pendiente y `BLOQUEADA` la rechaza; se bloquean las de exactamente 20.000 € | #14 (FRA-02), #16 (FRA-01) |
| Condición de carrera al añadir titulares | La comprobación del email bloqueaba la cuenta durante una llamada HTTP | El email se resuelve fuera de la transacción; test concurrente que demuestra que nunca hay más de 4 titulares | #21 (CAM-01) |
| Importe negativo o con decimales de más en transferencias | `-50` pasaba la validación de la API y el rechazo llegaba tarde y sin detalle del campo | `@DecimalMin("0.01")` y `@Digits(17,2)` en `CreateTransferRequest`; test `negativeAmountIs400` | #22 (SEG-03), #25 |
| Datos de demo sin titular | Con una BD nueva, ana y luis veían `GET /api/accounts` vacío | `DemoSeeder` guarda el `AccountHolder`; probado con `docker compose down -v` | #24 |
| Perfil `demo` y `/api/fraud/checks` | `demo` activo por defecto; los clientes podían ver las validaciones antifraude | `SPRING_PROFILES_ACTIVE` obligatorio y vacío en `.env.example`; `/api/fraud/checks` solo para gestores | #22 (SEG-03) |
| Extracto de movimientos incorrecto | Faltaba el día `to`, el filtro por fechas fallaba y se perdían movimientos al paginar | Se incluye el día `to`, se arregla el filtro y la paginación | #2, #9 (BUG-04), #10 (BUG-03) |
| Avisos duplicados y sin consulta | ms-notifications guardaba el mismo aviso varias veces y no tenía endpoint | `UNIQUE (user_id, movement_id)` con comprobación previa, y `GET /api/notifications` | #3 (KAF-01, KAF-03) |
| Tarjetas sin implementar | Emitir y pagar devolvían 501 | `CardService.issue` y `pay`; el límite diario solo cuenta los pagos aprobados | #13 (TAR-01) |
| IBAN que no existe sin controlar | Consultar un IBAN inexistente no tenía un error controlado | Control de error por IBAN no existente (`CUENTA_NO_ENCONTRADA`) | #4 (BUG-02) |
| CI que no probaba nada | El workflow no ejecutaba los tests, usaba un Testcontainers antiguo y una migración referenciaba un campo inexistente | El workflow lanza los tests, build de las 7 imágenes, migración y versión corregidas, ahora con `mvn -B verify` | #2 |
| Login con 500 por el puerto de `AUTH_URL` | El gateway llamaba a ms-auth en el 8018 en vez del 8081 | Corregido en `docker-compose.yml` (ver Fallo 1) | Sin PR propia identificada |

No aparecen aquí las PR de solo documentación (#1, #23) ni la #5, que se cerró sin mergear y quedó sustituida por la #6.

---

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

### Solución (ya aplicada)

La corrección está aplicada en [docker-compose.yml](/docker-compose.yml): `AUTH_URL` se define en la línea 8 y vale
`http://ms-auth:8081` (en el diagnóstico original estaba en la línea 12 con el puerto 8018):

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

### 3ª request
```bash
curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"email":"ana@bytebank.test","password":"Cliente1234!"}'
```

### 3ª response (OK: con el token, el tipo de token, y su duración)
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
- `MovementPublisher.publish` ya no habla con Kafka: guarda el evento en la tabla `outbox` (entidad `OutboxEvent`, repositorio `OutboxRepository`, migración `V5__outbox.sql`) **en la misma transacción que el movimiento**. Si hay rollback, desaparece también el evento.
- `OutboxRelay` (`@Scheduled`, cada 500 ms) toma un bloqueo advisory (`pg_try_advisory_xact_lock(1)`) para que solo una réplica vacíe la outbox a la vez, lee un lote de hasta 100 eventos, los envía a Kafka, espera la confirmación y los borra. Si Kafka falla, hace rollback y reintenta, así que no se pierde ningún aviso. No hay `FOR UPDATE SKIP LOCKED`: la exclusión entre réplicas es solo el bloqueo advisory.
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

---

## Fallo 4: importe negativo en transferencias

### Request

```bash
curl -s -X POST localhost:8080/api/transfers -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"originIban":"ES...","destinationIban":"ES...","amount":-50,"concept":"Prueba"}'
```

### Qué pasaba

`CreateTransferRequest.amount` solo tenía `@NotNull`, así que `-50` pasaba la validación de la API. El rechazo
llegaba después desde ms-fraud: su `CheckRequest` sí valida `@DecimalMin("0.01")` y devolvía un 400 genérico que
ms-transfers propagaba como `ApiException`. El cliente veía el mismo `DATOS_NO_VALIDOS` que si el error hubiera estado
en la API, pero sin `fields`, es decir, sin decir qué campo había fallado.

Respuesta obtenida sin la validación en `TransferDtos`:

```json
{
    "timestamp": "2026-11-22T22:12:03.138849632Z",
    "status": 400,
    "error": "Bad Request",
    "code": "DATOS_NO_VALIDOS",
    "message": "La petición tiene campos no válidos",
    "path": "/api/transfers"
}
```

Y la traza del rechazo en ms-fraud (los dos avisos `WARN` se generaron con logs temporales de depuración):

```
2026-11-22T22:21:36.004Z  WARN 1 --- [ms-fraud] [nio-8085-exec-2] d.b.common.error.GlobalExceptionHandler  : Validación fallida en POST /internal/fraud/check: {amount=must be greater than or equal to 0.01}
2026-11-22T22:21:36.021Z  WARN 1 --- [ms-transfers] [nio-8083-exec-2] d.b.common.error.GlobalExceptionHandler  : Error POST /api/transfers en 400 DATOS_NO_VALIDOS: La petición tiene campos no válidos
```

### Solución (PR #22)

`TransferDtos.java:20` añade `@DecimalMin("0.01")` y `@Digits(integer = 17, fraction = 2)` a `amount`, y
`TransferController.java:33` mantiene `@Valid`. El rechazo ocurre ahora en la frontera de la API y la respuesta
incluye el detalle del campo:

```json
{
    "timestamp": "2026-11-22T22:27:49.787367923Z",
    "status": 400,
    "error": "Bad Request",
    "code": "DATOS_NO_VALIDOS",
    "message": "La petición tiene campos no válidos",
    "path": "/api/transfers",
    "fields": {
        "amount": "must be greater than or equal to 0.01"
    }
}
```

### Test

`TransferControllerTest.negativeAmountIs400` envía `-50` a `POST /api/transfers`, espera 400 `DATOS_NO_VALIDOS` con
`fields.amount` y verifica que `TransferService` no llega a ejecutarse.