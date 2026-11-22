# Validación antifraude (ms-fraud)

ms-fraud recibe cada transferencia antes de ejecutarse y devuelve un veredicto:

| Veredicto | Qué significa |
|---|---|
| `APROBADA` | Se puede ejecutar |
| `REVISAR` | Un gestor tiene que revisarla antes de ejecutarla: ms-transfers la deja `PENDIENTE_REVISION` y no mueve dinero |
| `BLOQUEADA` | Se rechaza: ms-transfers la guarda como `RECHAZADA` con motivo `FRAUDE` y no mueve dinero |

ms-transfers llama a ms-fraud en cada transferencia, antes de ejecutarla, y aplica el veredicto tal cual. La regla
de importe alto (más de 5.000 €, configurable en `bytebank.fraud.review-threshold`) es una de las reglas de ms-fraud:
ms-transfers no tiene umbrales propios. Las `PENDIENTE_REVISION` las resuelve un gestor desde ms-transfers.

Si saltan varias reglas, gana el veredicto más grave. Cada validación queda guardada (tabla `fraud_checks`)
y se puede consultar en `GET /api/fraud/checks?iban=...`, restringido a usuarios `GESTOR` mediante
`@PreAuthorize("hasRole('GESTOR')")` en `FraudController.java:35` (PR #22). El histórico de este y otros hallazgos
está en [diagnostico.md](diagnostico.md).

## Reglas acordadas con negocio

| Regla | Código | Condición | Veredicto | Estado |
|---|---|---|---|---|
| Importe máximo por operación | `IMPORTE_MAXIMO` | Importe de 20.000 € o más | BLOQUEADA | Hecha |
| Transferencia a otra entidad | `EXTERNA_IMPORTE_ALTO` | Destino de otro banco e importe de 1.000 € o más | REVISAR | Hecha |
| Importe alto | `IMPORTE_REVISION` | Importe de más de 5.000 € a cualquier destino | REVISAR | Hecha |
| Frecuencia | `FRECUENCIA_ALTA` | Más de 5 transferencias desde la misma cuenta en 10 minutos (se bloquea la sexta) | BLOQUEADA | Hecha |


Los umbrales se configuran en `ms-fraud/src/main/resources/application.yml` (`bytebank.fraud.*`):
`block-threshold` (20000), `external-review-threshold` (1000), `review-threshold` (5000), `max-transfers` (5) y
`frequency-window` (10m).

## Contrato

`POST /internal/fraud/check` (con `X-Internal-Key`)

```json
{ "originIban": "ES...", "destinationIban": "ES...", "userId": 2, "amount": 1500.00 }
```

Respuesta:

```json
{ "checkId": 17, "verdict": "REVISAR", "reasons": ["EXTERNA_IMPORTE_ALTO"] }
```

## Qué hace ms-transfers con el veredicto

ms-transfers llama a ms-fraud en cada transferencia, después de comprobar la cuenta de origen y antes de mover dinero.

| Veredicto | Resultado |
|---|---|
| `APROBADA` | Se ejecuta con normalidad |
| `REVISAR` | Se guarda como `PENDIENTE_REVISION`, sin cargar nada |
| `BLOQUEADA` | Se guarda como `RECHAZADA` con motivo `FRAUDE`, sin cargar nada |

## Si ms-fraud no responde

**Decisión: la transferencia se rechaza** (`503 SERVICIO_NO_DISPONIBLE`). No se guarda ni se mueve dinero.

Por qué:

- Un control antifraude que se abre cuando se cae se puede saltar tirándolo.
- No queda nada que deshacer, así que el cliente puede reintentar sin riesgo.
- Mandarlas a `REVISAR` llenaría la cola de los gestores de transferencias que nadie ha evaluado.

Coste: sin ms-fraud no hay transferencias. Se podría mitigar más adelante con reintentos o un circuit breaker.
