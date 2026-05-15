# SentinelAI — API Reference

Base URL: `http://<host>/api`

All request and response bodies are JSON (`Content-Type: application/json`).

---

## Authentication

When API key authentication is enabled (i.e. `SENTINEL_API_KEYS` is set), every `POST /api/proxy` request must include:

```
X-Sentinel-Api-Key: <your-key>
```

Omitting the header, or providing an unrecognised key, returns `401 Unauthorized`.

The dashboard endpoints (`/api/logs`, `/api/dashboard`, `/api/license/info`) and the health endpoint (`/api/health`) do not require an API key.

---

## POST /api/proxy

Proxy a prompt to the configured AI model. The prompt is analysed for injection risk in parallel; the AI response is returned immediately.

### Request headers

| Header | Required | Description |
|---|---|---|
| `X-Sentinel-Api-Key` | Conditional | Required when API key auth is enabled |
| `X-Sentinel-License` | Optional | License key for tier-based log retention |
| `Content-Type` | Required | `application/json` |

### Request body

```json
{
  "prompt":   "Summarise the risks in this contract.",
  "model":    "gpt-4o",
  "provider": "OPENAI",
  "metadata": { "user_id": "u123", "department": "legal" }
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `prompt` | string | Yes | The prompt to send to the AI model |
| `model` | string | Yes | Model identifier (e.g. `gpt-4o`, `claude-3-5-sonnet-20241022`) |
| `provider` | string | No | Provider name: `OPENAI`, `ANTHROPIC`, `GEMINI`, `AZURE_OPENAI`. Defaults to `OPENAI` |
| `metadata` | object | No | Arbitrary key-value pairs persisted in the audit log |

### Response body (200 OK)

```json
{
  "requestId":    "a3f7c2d1-1234-5678-abcd-ef0123456789",
  "model":        "gpt-4o",
  "responseText": "The key risks are..."
}
```

### Error responses

| Status | Condition |
|---|---|
| `400 Bad Request` | Malformed JSON or missing required fields |
| `401 Unauthorized` | API key missing or invalid (when auth is enabled) |
| `429 Too Many Requests` | Rate limit exceeded; `Retry-After: 1` header included |
| `502 Bad Gateway` | AI model API returned an error |

---

## GET /api/logs

Retrieve a paginated, filtered list of audit log entries.

### Query parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `page` | integer | `0` | Zero-based page index |
| `size` | integer | `20` | Page size (max 200; requests above this are silently capped) |
| `from` | ISO 8601 datetime | — | Filter: entries on or after this time |
| `to` | ISO 8601 datetime | — | Filter: entries on or before this time |
| `minRisk` | decimal [0,1] | — | Filter: minimum risk score |
| `maxRisk` | decimal [0,1] | — | Filter: maximum risk score |

### Response body (200 OK)

```json
{
  "content": [
    {
      "id":           "uuid",
      "requestId":    "uuid",
      "prompt":       "What is the capital of France?",
      "responseText": "Paris.",
      "model":        "gpt-4o",
      "provider":     "OPENAI",
      "riskScore":    0.12,
      "licenseKey":   null,
      "metadata":     {},
      "requestTime":  "2025-06-01T14:32:00Z"
    }
  ],
  "totalElements": 142,
  "totalPages":    8,
  "number":        0,
  "size":          20
}
```

---

## GET /api/dashboard/stats

Retrieve summary statistics for the dashboard header cards, including the 30-day risk trend series.

### Response body (200 OK)

```json
{
  "totalLogs":      1420,
  "highRiskCount":  23,
  "mediumRiskCount": 187,
  "lowRiskCount":   1185,
  "nullRiskCount":  25,
  "dailySeries": [
    { "date": "2025-05-01", "highRiskCount": 3, "mediumRiskCount": 12, "lowRiskCount": 85 },
    { "date": "2025-05-02", "highRiskCount": 1, "mediumRiskCount": 9,  "lowRiskCount": 72 }
  ]
}
```

| Field | Description |
|---|---|
| `totalLogs` | Total number of audit log records |
| `highRiskCount` | Records with `riskScore >= 0.7` |
| `mediumRiskCount` | Records with `0.4 <= riskScore < 0.7` |
| `lowRiskCount` | Records with `riskScore < 0.4` |
| `nullRiskCount` | Records where the detection API timed out (no score) |
| `dailySeries` | Per-day aggregates for the last 30 days, oldest first |

---

## GET /api/license/info

Returns the license tier and retention policy for the configured license key.

### Request headers

| Header | Description |
|---|---|
| `X-Sentinel-License` | License key to resolve; omit for free-tier info |

### Response body (200 OK)

```json
{
  "tier":          "PAID_30",
  "retentionDays": 30
}
```

| Tier | Retention |
|---|---|
| `FREE` | 7 days |
| `PAID_30` | 30 days |
| `PAID_90` | 90 days |
| `PAID_365` | 365 days |

---

## GET /api/health

Application-level health check. Checks database connectivity and detection API reachability.

### Response body (200 OK — UP or DEGRADED)

```json
{
  "status":    "UP",
  "db":        { "status": "UP" },
  "detection": { "status": "UP" }
}
```

### Response body (200 OK — DEGRADED)

Detection API is unreachable; the proxy still functions (risk scores will be null).

```json
{
  "status":    "DEGRADED",
  "db":        { "status": "UP" },
  "detection": { "status": "DOWN", "message": "Connection refused" }
}
```

### Response body (503 Service Unavailable — DOWN)

Database is unreachable; the proxy cannot persist audit records.

```json
{
  "status":    "DOWN",
  "db":        { "status": "DOWN", "message": "Connection timed out" },
  "detection": { "status": "UP" }
}
```

### HTTP status mapping

| `status` | HTTP |
|---|---|
| `UP` | 200 |
| `DEGRADED` | 200 |
| `DOWN` | 503 |
