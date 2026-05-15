# Contributing to SentinelAI

Thank you for taking the time to contribute. This document explains how to get started and what to expect from the review process.

---

## Before you start

- **Bug reports** → open an issue using the [bug report template](.github/ISSUE_TEMPLATE/bug_report.yml)
- **Feature requests** → open an issue using the [feature request template](.github/ISSUE_TEMPLATE/feature_request.yml)
- **Security vulnerabilities** → read [SECURITY.md](SECURITY.md) first — do **not** open a public issue

For significant changes (new features, architectural changes, API modifications) please open an issue to discuss the approach before writing code. This avoids wasted effort if the direction doesn't fit the project.

---

## Development setup

Follow the [Full local development setup](README.md#full-local-development-setup) section in the README. The short version:

```bash
git clone https://github.com/vsrohithc/sentinelai.git
cd sentinelai
cp .env.example .env       # add your API keys
make db                    # start PostgreSQL
make backend               # start Spring Boot on :8080
make frontend              # start Vite dev server on :3000
make test                  # run all tests
```

---

## Branching and commit conventions

- Branch from `main`: `git checkout -b feat/my-feature` or `fix/issue-number-description`
- One logical change per pull request — keep PRs small and reviewable
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):
  - `feat: add Gemini streaming support`
  - `fix: correct risk score null handling in retention query`
  - `docs: expand deployment guide with TLS section`
  - `test: add RateLimitFilter unit tests`
  - `refactor: extract IP extraction logic from RateLimitFilter`
  - `chore: bump Spring Boot to 3.2.6`

---

## Code standards

### Backend (Java)

- Java 21, Spring Boot 3.2, Spring WebFlux (reactive, non-blocking)
- Follow existing package structure under `com.sentinelai`
- Constructor injection only — no field injection (`@Autowired` on fields)
- Every public class and method must have a Javadoc comment
- Use `@Slf4j` for logging; never use `System.out`
- No raw `ConcurrentHashMap` for caches — use Caffeine with eviction policies
- Run `mvn verify` before opening a PR — all tests must pass

### Frontend (TypeScript / React)

- React 18 + TypeScript (strict mode)
- Functional components + hooks only — no class components
- Tailwind CSS for styling — no inline styles, no external CSS files
- All API calls go through `src/api/` typed client — no `fetch` directly in components
- Run `npm run lint` and `npm run build` before opening a PR

### Database

- All schema changes must be Flyway migrations under `backend/src/main/resources/db/migration/`
- Migrations are named `V{n}__{description}.sql` (two underscores)
- Migrations must be idempotent where possible and safe to run on a live table
- Add `COMMENT ON COLUMN` for every new column

---

## Tests

- Unit tests live alongside the code they test under `src/test/`
- New features must include tests; bug fixes must include a regression test
- Integration tests use WireMock for external APIs and H2 (PostgreSQL compat mode) for the database
- Test class names end in `Test` (unit) or `IntegrationTest` (integration)
- Run the full suite: `cd backend && mvn test`

---

## Pull request checklist

Before requesting review:

- [ ] All tests pass (`make test`)
- [ ] Frontend builds without errors (`cd frontend && npm run build`)
- [ ] New code has Javadoc / TSDoc comments
- [ ] Database migrations are present for any schema changes
- [ ] `.env.example` updated if new environment variables were added
- [ ] `docs/` updated if the API or deployment procedure changed
- [ ] PR description explains *why* the change is needed, not just *what* it does

---

## Review process

- A maintainer will review within a few days
- Feedback will be left as inline comments on the PR
- Once approved and CI passes, a maintainer merges — contributors do not self-merge

---

## Code of conduct

Be respectful. Constructive criticism of ideas is welcome; criticism of people is not. Any contributor who creates a hostile environment will be removed from the project.
