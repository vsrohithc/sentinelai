# SentinelAI — Developer shortcuts
#
# Usage: make <target>
#
# Common workflows:
#   make demo     — one-click demo (no API keys needed, WireMock stubs everything)
#   make dev      — start full dev stack (DB + backend + frontend)
#   make test     — run all backend tests
#   make build    — build production Docker images
#   make prod     — start the production Docker stack
#   make clean    — stop and remove all containers and volumes
#
# Windows note: install Make via  choco install make  or  scoop install make
# then run from Git Bash, PowerShell, or Windows Terminal.

.PHONY: help demo dev db backend frontend test build prod clean lint check env

# ── OS detection (Windows vs Unix) ────────────────────────────────────────────
ifeq ($(OS),Windows_NT)
  DETECTED_OS := Windows
else
  DETECTED_OS := $(shell uname -s 2>/dev/null || echo Unknown)
endif

# ── Configuration ─────────────────────────────────────────────────────────────
COMPOSE        = docker compose
COMPOSE_DEV    = $(COMPOSE) -f docker/docker-compose.yml
COMPOSE_PROD   = $(COMPOSE) -f docker/docker-compose.prod.yml
COMPOSE_DEMO   = $(COMPOSE) -f docker/docker-compose.demo.yml
MVN            = mvn
NPM            = npm --prefix frontend

# ── Default target: print help ───────────────────────────────────────────────
help:
	@echo ""
	@echo "SentinelAI — available make targets:"
	@echo ""
	@echo "  make demo       One-click demo (WireMock stubs, no API keys needed)"
	@echo "  make dev        Start full dev stack (DB + backend + frontend)"
	@echo "  make db         Start PostgreSQL only (for local Spring Boot dev)"
	@echo "  make backend    Start Spring Boot backend (requires DB running)"
	@echo "  make frontend   Start React dev server (requires backend running)"
	@echo "  make test       Run all backend tests"
	@echo "  make lint       Lint frontend TypeScript"
	@echo "  make check      Run tests + lint (full pre-commit check)"
	@echo "  make build      Build production Docker images"
	@echo "  make prod       Start production stack (docker-compose.prod.yml)"
	@echo "  make clean      Stop everything and remove containers + volumes"
	@echo "  make env        Copy .env.example to .env (safe, won't overwrite)"
	@echo ""

# ── One-click demo ────────────────────────────────────────────────────────────
# Spins up PostgreSQL + WireMock + backend + frontend with no real API keys.
# WireMock stubs both the AI provider and the detection API.
# Seeds the audit log with sample requests automatically.
demo: env
	@echo "Starting SentinelAI demo stack..."
	$(COMPOSE_DEMO) up --build -d
	@echo ""
	@echo "  Dashboard -> http://localhost:3000"
	@echo "  API       -> http://localhost:8080"
	@echo "  WireMock  -> http://localhost:8090/__admin"
	@echo ""
	@echo "Tailing logs (Ctrl+C to stop tailing, stack keeps running)..."
	$(COMPOSE_DEMO) logs -f backend seed

# ── Full development stack ────────────────────────────────────────────────────
dev: env
	@echo "Starting development infrastructure..."
	$(COMPOSE_DEV) up -d
	@echo "PostgreSQL is up. Starting backend and frontend in parallel..."
	$(MAKE) -j2 backend frontend

# ── Database only (for local Spring Boot outside Docker) ─────────────────────
db: env
	$(COMPOSE_DEV) up -d postgres
	@echo "PostgreSQL ready on localhost:5432"

# ── Backend (Spring Boot via Maven wrapper) ───────────────────────────────────
backend:
	@echo "Starting Spring Boot backend on :8080..."
	cd backend && mvn spring-boot:run

# ── Frontend (Vite dev server) ────────────────────────────────────────────────
frontend:
	@echo "Starting React frontend on :3000..."
	$(NPM) install
	$(NPM) run dev

# ── Tests ─────────────────────────────────────────────────────────────────────
test:
	@echo "Running backend tests..."
	cd backend && mvn -B test --no-transfer-progress
	@echo "All tests passed."

# ── Lint ──────────────────────────────────────────────────────────────────────
lint:
	@echo "Linting frontend..."
	$(NPM) run lint
	$(NPM) run type-check

# ── Full pre-commit check ─────────────────────────────────────────────────────
check: test lint
	@echo "All checks passed."

# ── Production build ──────────────────────────────────────────────────────────
build:
	@echo "Building production Docker images..."
	docker build -t sentinelai-backend:latest backend/
	docker build -t sentinelai-frontend:latest frontend/
	@echo "Images built: sentinelai-backend:latest, sentinelai-frontend:latest"

# ── Production stack ──────────────────────────────────────────────────────────
prod: env
	@echo "Starting production stack..."
	$(COMPOSE_PROD) up -d
	@echo "Production stack is up."
	$(COMPOSE_PROD) ps

# ── Clean ─────────────────────────────────────────────────────────────────────
# The leading dash (-) tells Make to continue even if a command fails
# (e.g. a compose file's stack was never started). Safe on Windows and Unix.
clean:
	@echo "Stopping and removing all SentinelAI containers and volumes..."
	-$(COMPOSE_DEMO) down -v
	-$(COMPOSE_DEV) down -v
	-$(COMPOSE_PROD) down -v
	@echo "Clean complete."

# ── Env file helper ───────────────────────────────────────────────────────────
# Creates .env from .env.example if .env does not already exist.
# Uses OS-specific syntax so this works on Windows (cmd.exe) and Unix (bash).
ifeq ($(DETECTED_OS),Windows)
env:
	@if not exist .env (copy .env.example .env && echo Created .env from .env.example)
else
env:
	@test -f .env || (cp .env.example .env && echo "Created .env from .env.example — fill in your API keys before running.")
endif
