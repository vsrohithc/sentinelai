# Changelog

All notable changes to SentinelAI are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
SentinelAI uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- `provider` field on `PromptLog` — every audit record now captures which AI provider
  handled the request (OPENAI, ANTHROPIC, GEMINI, AZURE_OPENAI)
- V3 Flyway migration adds `provider` column with `DEFAULT 'OPENAI'` for safe deployment
  against existing data
- Bean Validation (`@Valid` / `@NotBlank`) on `ProxyRequest` DTO; `GlobalExceptionHandler`
  returns structured 400 responses with per-field error details
- `SecurityHeadersFilter` — adds X-Content-Type-Options, X-Frame-Options,
  Referrer-Policy, Permissions-Policy, Content-Security-Policy, and HSTS to every response
- `AsyncConfig` — bounded `ThreadPoolTaskExecutor` (core=4, max=20, queue=500) with
  graceful shutdown
- `UpstreamApiException` — typed exception thrown by provider adapters; mapped to
  HTTP 502 Bad Gateway by `GlobalExceptionHandler` so monitoring and clients can
  distinguish upstream-attributable failures from SentinelAI faults
- `ApiKeyFilter` now guards the audit-data read endpoints (`/api/logs`,
  `/api/dashboard`, `/api/license`) when `SENTINEL_API_KEYS` is set; `/api/health`
  remains public
- `provider`, `model`, and `risk_score` MDC fields populated in `ProxyService` so
  structured JSON logs carry the keys advertised in `logback-spring.xml`
- `RateLimitFilter` unit tests (9 assertions), `RequestContextFilter` unit tests (9
  assertions), and 4 new `ProxyIntegrationTest` scenarios covering validation and
  request-ID propagation
- One-click demo: `docker/docker-compose.demo.yml` with WireMock stubs for AI + detection
  APIs and a seed container that pre-populates 8 varied audit log entries
- `Makefile` with `make demo / dev / db / backend / frontend / test / lint / check /
  build / prod / clean` targets
- GitHub Actions CI: backend (mvn verify), frontend (lint + type-check + build), Docker
  image smoke-test; all jobs with `cancel-in-progress` concurrency control
- GitHub Actions Release workflow — tag-driven, publishes versioned images to GHCR
  and attaches the backend JAR + frontend zip to a GitHub Release
- GitHub issue templates (bug report, feature request), PR template, CONTRIBUTING.md,
  SECURITY.md, CODE_OF_CONDUCT.md, LICENSE (MIT), `.editorconfig`,
  `.github/FUNDING.yml`
- `dependabot.yml` for automated Maven, npm, and GitHub Actions dependency updates
- HikariCP connection pool tuning in `application.yml` (max=20, timeout=5 s, keepalive)

### Changed
- **Detection is now pluggable.** New `DetectionStrategy` interface plus three
  bundled implementations: `NoOpDetector` (default — pure governance, no scoring),
  `RuleBasedDetector` (in-process regex / keyword scoring, no data egress), and
  `ExternalApiDetector` (opt-in, POSTs prompts to a third-party HTTP detector).
  Selection via `DETECTION_STRATEGY=none|rules|external`. Default is `none` —
  prior behaviour (always-on external call) was a misalignment with the
  "self-hosted, prompts never leave your cloud" position and is now opt-in.
- `AsyncConfig` rejection policy: `CallerRunsPolicy` → `AbortPolicy`. The previous
  policy could run a blocking JDBC write on a Netty event-loop thread when the
  audit queue saturated; the new policy lets the bounded queue shed overflow audit
  records via the `AsyncUncaughtExceptionHandler` instead of stalling the server.
- `ProxyService` now uses `OffsetDateTime.now(ZoneOffset.UTC)` so `request_time` is
  comparable across instances regardless of the container's default time zone.
- CI: frontend dependency install falls back to `npm install` when `package-lock.json`
  is absent (with a warning), so a fresh clone before the lockfile is committed
  no longer breaks CI.

### Removed
- `AIModelClient` and the `aiModelWebClient` bean (dead Phase-2 code superseded by
  the `ModelProviderAdapter` strategy). Corresponding `sentinelai.ai-model.*`
  properties and `AI_MODEL_API_URL` / `AI_MODEL_API_KEY` env vars removed.
- `DetectionClient` and the `detectionWebClient` bean. Logic moved into the new
  `ExternalApiDetector` (under the `DetectionStrategy` abstraction). The old
  `sentinelai.detection.api-url` / `api-key` properties are renamed to
  `sentinelai.detection.external.api-url` / `api-key`; the `DETECTION_API_URL` /
  `DETECTION_API_KEY` env var names are unchanged (only consumed when
  `DETECTION_STRATEGY=external`).
- `WebClientConfig` — both beans it owned (`aiModelWebClient`, `detectionWebClient`)
  were dead after the refactors above; the whole class is removed.

### Fixed
- **Critical:** `SecurityHeadersFilter` previously set headers in `doFinally`, which runs
  after Netty commits the response — headers were silently dropped. Fixed to set headers
  before `chain.filter(exchange)`.
- `findDailyRiskCounts` rewritten from a PostgreSQL-only native query (using `TO_CHAR`,
  `DATE_TRUNC`, and `COUNT(*) FILTER`) to a portable JPQL query using
  `cast(... as LocalDate)` and `SUM(CASE WHEN ...)` — now runs correctly in the H2
  test context without dialect-specific SQL.
- Frontend `AuditLog` date filter: `<input type="datetime-local">` returns local time;
  the previous implementation appended `:00Z` and sent it as UTC, off by the user's
  timezone offset. Fixed by round-tripping through `Date`/`toISOString()`.
- `AuditLog` detail drawer can now be closed with the Escape key.

---

## [0.1.0] — 2026-05-04

### Added
- Initial open-source release
- Proxy endpoint (`POST /api/proxy`) supporting OpenAI, Anthropic, Google Gemini, and
  Azure OpenAI providers
- Parallel injection detection scoring via configurable detection API
- Asynchronous PostgreSQL audit logging (zero added latency to proxy path)
- React 18 dashboard with paginated audit log, risk-level filtering, and 30-day trend chart
- Per-tenant license tiers (FREE 7 d, PAID-30, PAID-90, PAID-365) with nightly retention
  cleanup
- Bucket4j token-bucket rate limiting per IP with Caffeine cache
- API key authentication via `X-Sentinel-Api-Key` header (opt-in)
- Structured JSON logging (Logback + logstash-logback-encoder) with MDC request IDs
- `GET /api/health` endpoint with DB + detection API connectivity checks
- Flyway schema migrations (V1, V2)
- Multi-stage Dockerfiles for backend and frontend
- Docker Compose files for development and production stacks
- Documentation: architecture, API reference, deployment guide

[Unreleased]: https://github.com/vsrohithc/sentinelai/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/vsrohithc/sentinelai/releases/tag/v0.1.0
