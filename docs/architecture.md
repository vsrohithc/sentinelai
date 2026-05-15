# SentinelAI — Architecture

## Overview

SentinelAI is an AI governance proxy. Every prompt sent to an AI model is intercepted, analysed for injection risk, and logged — before the response is returned to the caller. The proxy adds **zero latency** to the AI call by running the risk detection in parallel.

```
Client
  │
  ▼
┌─────────────────────────────────────────────────────────────────┐
│  Nginx  (frontend + /api/* reverse proxy)                       │
└────────────────────────────┬────────────────────────────────────┘
                             │ POST /api/proxy
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│  Spring Boot Backend  (WebFlux / Netty)                         │
│                                                                 │
│  RequestContextFilter → ApiKeyFilter → RateLimitFilter → ProxyController → SecurityHeadersFilter │
│                                        │                        │
│                              Mono.zip()  (parallel)             │
│                             ┌──────────┴──────────┐            │
│                             ▼                     ▼            │
│                     AI Model API         Detection API         │
│                     (provider-routed)    (injection risk)      │
│                             │                     │            │
│                             └──────────┬──────────┘            │
│                                        │                        │
│                          Return AI response immediately         │
│                                        │                        │
│                         @Async audit persist (non-blocking)    │
│                                        │                        │
│                               PostgreSQL (prompt_logs)         │
└─────────────────────────────────────────────────────────────────┘
```

## Request flow

Filters execute in priority order (lowest number = first):

| Order | Filter | Priority | Responsibility |
|---|---|---|---|
| 1 | `RequestContextFilter` | `MIN_VALUE` | Generates / propagates `X-Request-Id`; sets MDC fields (`request_id`) for structured logging |
| 2 | `ApiKeyFilter` | `MIN_VALUE + 10` | Validates `X-Sentinel-Api-Key` header; returns `401` when auth is enabled and key is missing or invalid. No-op when no keys are configured |
| 3 | `RateLimitFilter` | `MIN_VALUE + 20` | Checks per-IP token bucket (Bucket4j + Caffeine). Returns `429` with `Retry-After: 1` when bucket is empty |
| 4 | `SecurityHeadersFilter` | `MAX_VALUE` | Adds security response headers (`X-Frame-Options`, `CSP`, `X-Content-Type-Options`, etc.) **before** `chain.filter()` — Netty commits the response on first write, so headers set after that point are silently dropped |

1. **RequestContextFilter** — assigns a `UUID` request ID to the `X-Request-Id` header and MDC so all log lines for a single request share the same correlation ID.

2. **ApiKeyFilter** — validates the `X-Sentinel-Api-Key` header. Returns `401` if authentication is enabled and the key is missing or unrecognised. No-op when no keys are configured.

3. **RateLimitFilter** — checks the per-IP token bucket (Bucket4j). Returns `429 Too Many Requests` with `Retry-After: 1` when the bucket is empty.

4. **ProxyController** — reads `ProxyRequest` body and `X-Sentinel-License` header; delegates to `ProxyService`.

4. **ProxyService** — fires two parallel calls using `Mono.zip()`:
   - **AI model call** — routed via `ModelProvider` to the appropriate adapter (OpenAI, Anthropic, Gemini, Azure OpenAI).
   - **Detection strategy** — pluggable. Default `NoOpDetector` returns an empty Mono (audit row gets `risk_score = NULL`). `RuleBasedDetector` runs regex scoring in-process. `ExternalApiDetector` POSTs the prompt to a configured third-party HTTP API (opt-in only — sends prompt text out of the environment).

5. **ProxyService** — as soon as `Mono.zip()` resolves, the AI response is returned to the caller via HTTP. `SecurityHeadersFilter` has already applied response headers before the first bytes are written.

6. **AuditService** — persists the full audit record (prompt, response, risk score, request metadata) asynchronously on a separate `@Async` thread pool. The HTTP response has already been sent before this write completes.

## Components

### Backend (`backend/`)

| Package | Responsibility |
|---|---|
| `controller` | HTTP endpoints — ProxyController, LogController, DashboardController, LicenseController, HealthController |
| `service` | Business logic — ProxyService, AuditService, LogQueryService, LicenseService, RetentionCleanupService, HealthService |
| `client` | External API clients — ModelProviderAdapter implementations (OpenAI, Anthropic, Gemini, Azure), DetectionClient |
| `config` | Spring configuration — WebClientConfig, ProviderConfig, RateLimitConfig, WebConfig (contains `SecurityHeadersFilter` as an inner `@Component`) |
| `filter` | WebFilters — RequestContextFilter, ApiKeyFilter, RateLimitFilter |
| `model` | JPA entity (PromptLog), enums (ModelProvider, LicenseTier) |
| `dto` | Request/response DTOs |
| `repository` | Spring Data JPA repository with custom JPQL queries |

### Frontend (`frontend/`)

A React single-page application (Vite + TypeScript + TanStack Query):

- **Dashboard** — risk trend chart (daily high/medium/low counts) and summary stats.
- **Audit Log** — paginated, filterable table of all proxy calls with risk scores.
- **Settings** — license tier information and retention policy.

### Database (PostgreSQL 16)

Single table `prompt_logs` managed by Flyway migrations:

```
prompt_logs
├── id              UUID  (PK, generated)
├── request_id      UUID  (unique, correlates proxy response to log record)
├── prompt          TEXT  (full prompt text)
├── response_text   TEXT  (full AI response)
├── model           TEXT  (e.g. gpt-4o, claude-3-5-sonnet-20241022)
├── provider        TEXT  (OPENAI | ANTHROPIC | GEMINI | AZURE_OPENAI)
├── risk_score      NUMERIC(5,4)  (null if detection timed out)
├── license_key     TEXT  (null for free tier)
├── metadata        JSONB (caller-supplied key/value pairs)
└── request_time    TIMESTAMPTZ (indexed)
```

## Multi-provider routing

```
POST /api/proxy { "provider": "ANTHROPIC", ... }
         │
         ▼
ModelProvider.fromString("ANTHROPIC") → ModelProvider.ANTHROPIC
         │
         ▼
Map<ModelProvider, ModelProviderAdapter>.get(ANTHROPIC) → AnthropicAdapter
         │
         ▼
POST https://api.anthropic.com/v1/messages
```

Unknown or missing providers fall back to `OPENAI`.

## Rate limiting

Bucket4j token-bucket algorithm, one bucket per client IP stored in a `ConcurrentHashMap`. Default: 60 tokens capacity, 60 tokens refilled per minute (≈1 req/s sustained, 60 req burst). Configurable via `RATE_LIMIT_CAPACITY` and `RATE_LIMIT_REFILL_PER_MINUTE`.

In a multi-node deployment, replace `ConcurrentHashMap` with Redis-backed Bucket4j storage so limits are shared across instances.

## Logging

- **Development**: human-readable ANSI console via `logback-spring.xml` `!prod` profile.
- **Production**: structured JSON via `logstash-logback-encoder`, one log line per event, captured by Docker's log driver and shipped to a log aggregator (Datadog, ELK, CloudWatch, Splunk).

MDC fields set per-request: `request_id`, `provider`, `model`, `risk_score`.

## Log retention

A nightly `@Scheduled` job (`RetentionCleanupService`) deletes records older than the threshold for each license tier:

| Tier | Retention |
|---|---|
| Free (no license key) | 7 days |
| PAID_30 | 30 days |
| PAID_90 | 90 days |
| PAID_365 | 365 days |
