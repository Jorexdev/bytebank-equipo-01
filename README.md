# ByteBank

Backend de la banca online de ByteBank: cuentas, transferencias, tarjetas de débito, validación antifraude y avisos
de movimientos. Microservicios con Spring Boot que se comunican por HTTP (y por Kafka para los avisos), con una base
de datos PostgreSQL por servicio.

## Servicios

| Servicio | Puerto | Qué hace |
|---|---|---|
| ms-gateway | 8080 | Punto de entrada. Solo enruta las rutas `/api/**` |
| ms-auth | 8081 | Registro, login y emisión de tokens JWT |
| ms-accounts | 8082 | Cuentas, saldo y movimientos (extracto) |
| ms-transfers | 8083 | Transferencias entre cuentas |
| ms-cards | 8084 | Tarjetas de débito y pagos |
| ms-fraud | 8085 | Validación antifraude de transferencias |
| ms-notifications | 8086 | Avisos al cliente de cada movimiento (consume eventos de Kafka) |

Más detalle en [docs/arquitectura.md](docs/arquitectura.md).

## Stack

Java 17, Spring Boot 3.3.5, Spring Security con JWT, Spring Data JPA, Flyway, PostgreSQL 15, Spring Cloud Gateway,
Apache Kafka (modo KRaft), JUnit 5, Mockito, Testcontainers, Docker Compose y GitHub Actions.

## Arrancar en local

```bash
cp .env.example .env      # y cambia las claves
docker compose up --build
```

| Variable de entorno | Contenido | Notas |
|---|---|---|
| `POSTGRES_PASSWORD` | Contraseña de PostgreSQL| Obligatoria: Compose no arranca si falta o está vacía. |
| `INTERNAL_KEY` | Clave de las llamadas internas (cabecera `X-Internal-Key` en `/internal/**`). | Obligatoria: Compose no arranca si falta o está vacía. |
| `JWT_SECRET` | Clave de firma HS256 de los tokens. | Obligatoria y de al menos 32 bytes (256 bits); Compose no arranca si falta o está vacía. Genera uno con `openssl rand -base64 48`.|
| `SPRING_PROFILES_ACTIVE` | Perfil de Spring: `demo` crea usuarios y cuentas de prueba; `prod` para despliegues. | Obligatoria: Compose no arranca si falta o está vacía. Usa `demo` solo en desarrollo. |

### Tiempo de expiración

`JWT_EXPIRATION_MINUTES` se define en `docker-compose.yml` (1440 minutos = 24 h por defecto). No es secreto;
para un banco se recomienda reducirlo (p. ej. 15–60 min).

La API queda en `http://localhost:8080`. Cada servicio publica su Swagger en
`http://localhost:<puerto>/swagger-ui.html` (por ejemplo, `http://localhost:8082/swagger-ui.html` para ms-accounts).

Con `SPRING_PROFILES_ACTIVE=demo` en `.env` (solo desarrollo; en despliegues usa `prod`) se crean estos usuarios y
unas cuentas con saldo:

| Usuario | Contraseña | Rol |
|---|---|---|
| gestor@bytebank.test | Gestor1234! | GESTOR |
| ana@bytebank.test | Cliente1234! | CLIENTE (2 cuentas) |
| luis@bytebank.test | Cliente1234! | CLIENTE (1 cuenta) |

Ejemplo:

```bash
curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"ana@bytebank.test","password":"Cliente1234!"}'
# usa el token devuelto en la cabecera Authorization: Bearer <token>
curl -s localhost:8080/api/accounts -H "Authorization: Bearer $TOKEN"
```

## Compilar y tests

```bash
./mvnw clean verify
```

Los tests de integración usan Testcontainers, así que necesitan Docker arrancado.

## Estado del proyecto (noviembre de 2026)

Contrastado con `main` (commit `b3a76fb`, que incluye el PR #22 de ajustes de seguridad).

**Ya implementado:**

- **Antifraude conectado**: ms-transfers consulta a ms-fraud antes de mover dinero. Un veredicto `REVISAR` deja la
  transferencia `PENDIENTE_REVISION` sin cargo ni abono, y un `BLOQUEADA` la guarda como `RECHAZADA` con motivo
  `FRAUDE`. Un gestor ve la cola en `GET /api/transfers/pending` y resuelve cada una con
  `POST /api/transfers/{id}/approve` o `POST /api/transfers/{id}/reject`; solo se puede resolver una vez.
  Ver [docs/fraude.md](docs/fraude.md).
- **Cuentas con varios titulares**: `POST /api/accounts/{iban}/holders` añade titulares por email (máximo 4) y las
  respuestas de cuentas incluyen `holderIds`. Tarjetas, transferencias y avisos tienen en cuenta a todos los titulares.
- **Tarjetas**: emitir, pagar, listar, bloquear/desbloquear y consultar el historial de pagos funcionan.
  Ver [docs/contratos/ms-cards.md](docs/contratos/ms-cards.md).
- **Avisos**: `GET /api/notifications` permite consultarlos y ms-accounts publica los movimientos con patrón
  *outbox*, así que no se avisa de operaciones que hicieron rollback.
  Ver [docs/contratos/ms-notifications.md](docs/contratos/ms-notifications.md).
- **Extracto**: paginado y con filtro de fechas en la zona horaria de negocio (`Europe/Madrid`).
- **CI**: `ci.yml` ejecuta `mvn -B verify` y construye las 7 imágenes.
- **Importes de transferencia validados**: `CreateTransferRequest.amount` exige un valor mayor que 0 y como máximo 2
  decimales (`@DecimalMin` y `@Digits`), además de la validación ya existente en ms-fraud y en el ledger.
- **Acceso a las validaciones antifraude restringido**: solo un `GESTOR` puede consultar `GET /api/fraud/checks`.
- **Perfil `demo` bajo petición**: ya no es el valor por defecto; `SPRING_PROFILES_ACTIVE` es obligatorio y los seeders
  solo se activan cuando vale `demo`.