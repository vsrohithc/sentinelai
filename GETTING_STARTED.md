# Getting Started

This guide takes a brand-new contributor from "I just landed on the GitHub page" to "the dashboard is open in my browser." If you're only interested in trying SentinelAI without writing code, the **One-click demo** path is enough.

> Looking for the full project overview? See [README.md](README.md).
> Want to contribute? See [CONTRIBUTING.md](CONTRIBUTING.md) after finishing this guide.

---

## 1 — Prerequisites

### Option A — One-click demo (zero developer tooling)

You only need two things on your machine:

| Tool | Tested version | Where to get it |
|---|---|---|
| **Git** | any recent version | <https://git-scm.com/downloads> |
| **Docker Desktop** | 4.x (ships Compose v2) | <https://www.docker.com/products/docker-desktop> |

That's it. No Java. No Node. No API keys. Everything is mocked by WireMock inside Docker.

### Option B — Full local development

If you intend to edit code or run the backend/frontend outside Docker, install:

| Tool | Tested version | Notes |
|---|---|---|
| Git | any recent | |
| **JDK 21** | Temurin 21 | <https://adoptium.net/temurin/releases/?version=21> |
| **Maven** | 3.9+ | <https://maven.apache.org/download.cgi> |
| **Node.js** | 20 LTS | <https://nodejs.org/en/download> |
| **Docker Desktop** | 4.x | Used to run PostgreSQL locally |
| **GNU Make** | any | macOS: `brew install make` · Windows: `choco install make` · Linux: built-in |

Verify each one before continuing:

```bash
git --version
java -version          # should report 21
mvn -version           # should report 3.9+
node --version         # should report v20.x
docker --version
make --version
```

---

## 2 — Clone

```bash
git clone https://github.com/vsrohithc/sentinelai.git
cd sentinelai
```

---

## 3 — Run

### Path A — One-click demo

```bash
docker compose up --build -d
```

Wait roughly 60–90 seconds for the first build (only on the first run; subsequent starts are seconds). Then open:

- Dashboard → <http://localhost:3000>
- API base → <http://localhost:8080>
- WireMock admin (mocked AI + detection APIs) → <http://localhost:8090/__admin>

The seed container fires 16 sample proxy requests automatically, so the dashboard is pre-populated.

To stop:

```bash
docker compose down            # stop containers, keep data
docker compose down -v         # stop AND wipe the database volume
```

### Path B — Full local development

```bash
cp .env.example .env           # fill in OPENAI_API_KEY (or another provider) + DETECTION_API_*
make db                        # start PostgreSQL (Docker)
make backend                   # start Spring Boot on :8080  (separate terminal)
make frontend                  # start Vite dev server on :3000  (separate terminal)
```

When you change Java code, restart `make backend`. When you change TypeScript, Vite hot-reloads automatically.

To stop:

```bash
# Ctrl+C in each foreground terminal, then:
docker compose -f docker/docker-compose.yml down       # stops PostgreSQL
```

---

## 4 — Verify it works

Send a test request:

```bash
curl -X POST http://localhost:8080/api/proxy \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "Summarise the risks in this contract.",
    "model": "gpt-4o",
    "provider": "OPENAI"
  }'
```

Expected response:

```json
{
  "requestId": "a3f7c2d1-…",
  "model": "gpt-4o",
  "responseText": "The key risks are…"
}
```

Open <http://localhost:3000/logs> — the request appears at the top of the audit log within a second.

---

## 5 — Run the tests

```bash
make test         # backend unit + integration tests (JUnit 5 + WireMock + H2)
make lint         # frontend ESLint + tsc --noEmit
make check        # both of the above (use this before opening a PR)
```

The full suite finishes in roughly 60–90 seconds on a recent laptop.

---

## 6 — What's next

- **Edit the code** — start with [`docs/architecture.md`](docs/architecture.md) for the component map.
- **Add an AI provider** — pattern documented in [README.md → Adding a new AI provider](README.md). Touches `ModelProvider` enum, a new `*Adapter` class, `ProviderConfig`, and `application.yml`.
- **Write a PR** — read [CONTRIBUTING.md](CONTRIBUTING.md) for branch/commit conventions and the PR checklist.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `port is already allocated` on `make demo` | Another service is on 3000/8080/8090/5432 | `docker compose down`, then change the host port in `compose.yaml`. |
| Backend logs `Flyway migration … failed` | The DB was previously initialised with an incompatible schema | `docker compose down -v` to wipe the volume, then `up` again. |
| Frontend shows "Failed to load…" on every page | Backend isn't reachable from the browser | Open <http://localhost:8080/api/health> — if it 404s, the backend isn't up; check `docker compose logs backend`. |
| `make backend` fails with "no main class" | Maven didn't compile cleanly | Run `cd backend && mvn clean package -DskipTests` and inspect the build output. |
| `npm run dev` fails with "Cannot find module" | `node_modules` is missing or stale | `cd frontend && rm -rf node_modules && npm install`. |
| Tests fail with "WireMock server failed to start" | A stale Java process is holding a port | `jps` and kill any leftover `surefire`-named processes; rerun `make test`. |

If something else breaks, open an issue using the [bug report template](.github/ISSUE_TEMPLATE/bug_report.yml).
