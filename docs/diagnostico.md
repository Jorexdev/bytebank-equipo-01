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