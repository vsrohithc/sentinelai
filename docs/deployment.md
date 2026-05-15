# SentinelAI — Deployment Guide

## Prerequisites

- Docker 24+ and Docker Compose v2
- A running prompt injection detection API (or a stub — see [Detection API](#detection-api))
- API keys for the AI providers you intend to use

---

## Quick start

### 1. Clone and configure

```bash
git clone https://github.com/vsrohithc/sentinelai.git
cd sentinelai
cp .env.example .env
```

Edit `.env` and fill in every value. Required fields are marked below.

### 2. Build the images

```bash
docker build -t sentinelai-backend:latest ./backend
docker build -t sentinelai-frontend:latest ./frontend
```

To pass Vite build-time variables to the frontend image:

```bash
docker build \
  --build-arg VITE_API_BASE_URL="" \
  --build-arg VITE_LICENSE_KEY="sk-sentinel-..." \
  -t sentinelai-frontend:latest \
  ./frontend
```

### 3. Start the stack

```bash
docker-compose -f docker/docker-compose.prod.yml up -d
```

### 4. Verify

```bash
# Overall health
curl http://localhost/api/health

# First proxy call
curl -X POST http://localhost/api/proxy \
  -H "Content-Type: application/json" \
  -H "X-Sentinel-Api-Key: your-key" \
  -d '{"prompt":"Hello world","model":"gpt-4o","provider":"OPENAI"}'
```

The dashboard is available at `http://localhost`.

---

## Environment variables

Copy `.env.example` to `.env` and fill in all values before starting the stack.

### Required

| Variable | Description |
|---|---|
| `POSTGRES_DB` | PostgreSQL database name (e.g. `sentinelai`) |
| `POSTGRES_USER` | PostgreSQL username |
| `POSTGRES_PASSWORD` | PostgreSQL password — use a strong random value |

### AI providers (supply at least one)

| Variable | Description |
|---|---|
| `OPENAI_API_KEY` | OpenAI API key (`sk-...`) |
| `ANTHROPIC_API_KEY` | Anthropic API key |
| `GEMINI_API_KEY` | Google AI Studio or Vertex AI API key |
| `AZURE_OPENAI_ENDPOINT` | Azure OpenAI resource endpoint (e.g. `https://my.openai.azure.com`) |
| `AZURE_OPENAI_API_KEY` | Azure OpenAI API key |
| `AZURE_OPENAI_DEPLOYMENT` | Azure deployment name |
| `AZURE_OPENAI_API_VERSION` | Azure API version (default: `2024-02-01`) |

### Optional

| Variable | Default | Description |
|---|---|---|
| `SENTINEL_API_KEYS` | *(empty — auth disabled)* | Comma-separated list of valid API keys for `/api/proxy` and audit-data endpoints |
| `DETECTION_STRATEGY` | `none` | One of `none` (no scoring), `rules` (in-process), `external` (third-party API) |
| `DETECTION_API_URL` | *(empty)* | External detection URL (only used when `DETECTION_STRATEGY=external`) |
| `DETECTION_API_KEY` | *(empty)* | API key for the external detection service |
| `DETECTION_TIMEOUT_MS` | `5000` | Max ms to wait for an external detection response |
| `RATE_LIMIT_CAPACITY` | `60` | Token bucket capacity per IP (burst limit) |
| `RATE_LIMIT_REFILL_PER_MINUTE` | `60` | Tokens refilled per minute per IP |
| `FRONTEND_PORT` | `80` | Host port for the Nginx frontend container |
| `SERVER_PORT` | `8080` | Internal backend port (do not change unless there is a conflict) |
| `SENTINEL_LICENSES` | *(empty)* | License key map, e.g. `{sk-sentinel-abc: PAID_90}` |

---

## Detection strategy

SentinelAI's primary value is the audit trail. Scoring each prompt for injection risk is an optional, pluggable layer. Pick one of:

| `DETECTION_STRATEGY` | Behaviour | Data leaves your environment? | Per-request cost |
|---|---|---|---|
| `none` *(default)* | No scoring; `risk_score = NULL` on every audit row. | No | $0 |
| `rules` | In-process regex / keyword scorer covering well-known prompt-injection patterns. | No | $0 |
| `external` | POSTs the prompt to `${DETECTION_API_URL}/analyze` with body `{"text": "<prompt>"}` and reads `{"score": 0.0–1.0}` from the response. | **Yes** | Vendor pricing |

The `external` strategy is wire-compatible with most prompt-injection detection vendors (Lakera, Protect AI, …). If your vendor uses a different wire format, point `external` at a thin translating proxy you control, or open a PR adding a vendor-specific `DetectionStrategy` implementation.

If detection is unavailable or times out, `risk_score` is stored as `null` — the proxy call is never blocked.

For local development against the `external` strategy you can run a WireMock stub:

```bash
java -jar wiremock-standalone.jar --port 8090 &
curl -X POST http://localhost:8090/__admin/mappings \
  -d '{"request":{"method":"POST","url":"/analyze"},"response":{"status":200,"body":"{\"score\":0.1}","headers":{"Content-Type":"application/json"}}}'
export DETECTION_STRATEGY=external
export DETECTION_API_URL=http://localhost:8090
```

---

## Updating

To deploy a new version with zero data loss:

```bash
# 1. Build new images
docker build -t sentinelai-backend:latest ./backend
docker build -t sentinelai-frontend:latest ./frontend

# 2. Restart containers (Flyway runs migrations automatically on startup)
docker-compose -f docker/docker-compose.prod.yml up -d --force-recreate backend frontend
```

PostgreSQL data is stored in the `sentinelai-postgres-data` named volume and survives container restarts.

---

## Backup and restore

### Backup

```bash
docker exec sentinelai-postgres \
  pg_dump -U $POSTGRES_USER $POSTGRES_DB \
  > backup-$(date +%Y%m%d).sql
```

### Restore

```bash
docker exec -i sentinelai-postgres \
  psql -U $POSTGRES_USER $POSTGRES_DB \
  < backup-20250601.sql
```

---

## Scaling (multi-node)

The current architecture is single-node:

- Rate limit state is stored in-memory (per JVM instance).
- No distributed cache or coordination between nodes.

To run multiple backend instances behind a load balancer:

1. Replace `ConcurrentHashMap` in `RateLimitConfig` with Redis-backed Bucket4j (`bucket4j-redis` module).
2. Add Redis as a service in `docker-compose.prod.yml`.
3. Configure a load balancer (Nginx upstream, AWS ALB, etc.) in front of the backend containers.

---

## Health checks

| Endpoint | Description |
|---|---|
| `GET /api/health` | Application-level check (DB + detection API) |
| `GET /actuator/health` | Spring Boot Actuator check (DB, disk space, etc.) |
| `GET /actuator/metrics` | JVM and HTTP metrics |

The `depends_on` in `docker-compose.prod.yml` uses the container-level `HEALTHCHECK` instructions so that services start in the correct order: `postgres` → `backend` → `frontend`.

---

## Troubleshooting

**Backend fails to start with `FlywayException`**

Flyway could not connect to the database. Check:
- `POSTGRES_*` variables match what PostgreSQL was initialised with.
- The `postgres` container is healthy: `docker inspect sentinelai-postgres | grep Status`.

**`POST /api/proxy` returns 401**

- Check `SENTINEL_API_KEYS` is set correctly.
- Verify the `X-Sentinel-Api-Key` header matches one of the configured keys (exact string match, no extra whitespace).
- If you want to disable auth, set `SENTINEL_API_KEYS=` (empty string).

**All risk scores are null**

The detection API is unreachable. Check:
- `DETECTION_API_URL` is correct and the service is running.
- `GET /api/health` reports `detection.status`.
- `DETECTION_TIMEOUT_MS` is large enough for your detection provider's latency.

**Dashboard shows no data**

- Verify the Nginx proxy is forwarding `/api/*` to the backend (check `nginx.conf`).
- Call `GET /api/logs` directly: `curl http://localhost/api/logs`.
- Check backend logs: `docker logs sentinelai-backend`.
