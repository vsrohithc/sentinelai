# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| `main` branch | Yes — receives security fixes |
| Older releases | No |

---

## Reporting a vulnerability

**Please do not open a public GitHub issue for security vulnerabilities.**

Security issues that are reported publicly before a fix is available can put users at risk. Instead:

1. **Open a [GitHub Private Security Advisory](https://github.com/vsrohithc/sentinelai/security/advisories/new)**  
   This keeps the report confidential until a fix is released.

2. Include in your report:
   - A description of the vulnerability and its potential impact
   - Steps to reproduce or a proof-of-concept (if safe to share)
   - Affected versions (if known)
   - Your suggested fix (optional but appreciated)

3. You will receive an acknowledgement within **48 hours** and a status update within **7 days**.

4. Once a fix is ready, we will coordinate a disclosure timeline with you before publishing the patch and a security advisory.

---

## Scope

The following are in scope for security reports:

- Authentication or authorisation bypass in the proxy or dashboard
- SQL injection or data exfiltration via the audit log endpoints
- Rate limiting bypass that allows resource exhaustion
- Injection of malicious content into persisted audit records
- Any vulnerability in the supply chain (dependencies) with a CVSS score ≥ 7.0

The following are **out of scope**:

- Vulnerabilities in the AI providers themselves (OpenAI, Anthropic, etc.)
- Vulnerabilities in the injection detection service you plug in
- Issues that require physical access to the server
- Social engineering of project maintainers

---

## Security design notes

SentinelAI is designed with the following security properties:

- **API key authentication** — when `SENTINEL_API_KEYS` is set, every authenticated endpoint requires a valid `X-Sentinel-Api-Key` header. That includes `POST /api/proxy` *and* the audit-data read endpoints (`/api/logs`, `/api/dashboard/stats`, `/api/license/info`). `/api/health` is intentionally not guarded so load balancers can reach it. **Production deployments should always set `SENTINEL_API_KEYS`** — leaving it blank disables authentication on every endpoint, including the audit trail.
- **Per-IP rate limiting** — Bucket4j token-bucket with configurable capacity and refill rate
- **Input validation** — Bean Validation on all request bodies; oversized prompts are rejected with HTTP 400
- **Security headers** — `X-Content-Type-Options`, `X-Frame-Options`, `Content-Security-Policy`, `Strict-Transport-Security` on all responses
- **CORS** — configurable `CORS_ALLOWED_ORIGINS`; credentials not allowed by default
- **Structured logging** — all logs are JSON in production with MDC correlation IDs; no sensitive data (API keys, prompt content) is logged at INFO or above
- **Data residency** — prompt and response data is stored in your own PostgreSQL instance. With the default `DETECTION_STRATEGY=none` or `=rules`, no prompt content leaves the deployment. The `external` strategy is opt-in and is the only configuration that POSTs prompt text to a third party (the operator's chosen detection vendor); see [README → Detection strategies](README.md#detection-strategies-pluggable)
- **Dependency updates** — Spring Boot BOM manages transitive dependency versions; update regularly

---

## Dependency vulnerabilities

If you find a known CVE in a dependency used by SentinelAI:

1. Check whether the latest version of SentinelAI already bumps past the vulnerable version
2. If not, open a **public** GitHub issue (dependency CVEs are not usually embargoed) linking to the CVE and suggesting the fix
