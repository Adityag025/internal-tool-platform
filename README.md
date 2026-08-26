# Internal Developer Tool Distribution & Automated Release Platform

A **learning / reconstruction project** that builds, from scratch, the kind of
internal platform engineering teams use to build, test, version, distribute
and deploy internal tools.

> This project is *inspired by* release-engineering responsibilities — CI/CD
> pipeline optimisation, data-driven integration testing, and an internal
> artifact repository with dynamic tool versioning. It is **not** a
> reimplementation of any employer's internal system, and nothing here should
> be described as work done for an employer. See
> [`docs/interview-prep.md`](docs/interview-prep.md) for how to talk about it
> honestly.

## The problem in one table

| Client   | Needs                | Why |
|----------|----------------------|-----|
| Client A | `data-validator 1.0` | Frozen for an audit |
| Client B | `data-validator 1.5` | Mid-migration |
| Client C | `data-validator 2.0` | On the newest schema |

"Latest" cannot serve all three. So the unit of distribution is
**(tool, version)**, and every consumer states the exact version it wants.

## Status

Phases 1-2 of 9 complete — see [`docs/roadmap.md`](docs/roadmap.md).

| Phase | Deliverable | Status |
|-------|-------------|--------|
| 1 | Tool Registry Service (Spring Boot + PostgreSQL) | **done** |
| 2 | Clients & version pinning | **done** |
| 3 | Artifactory integration & artifact download | pending |
| 4 | Python data-driven pytest framework | pending |
| 5 | Baseline CI pipeline + measurement | pending |
| 6 | Optimised CI pipeline + before/after numbers | pending |
| 7 | Docker image, full Compose stack, TypeScript client | pending |
| 8 | AWS (ECR + ECS) | pending |
| 9 | Observability, security, interview pack | pending |

## Tech stack

Java 21 · Spring Boot 3.5 · Spring Web / Data JPA / Validation / Actuator ·
PostgreSQL 16 · Flyway · Maven (wrapper) · JUnit 5 · Testcontainers · Docker
Compose. Later phases add Python + pytest, JFrog Artifactory, GitHub Actions,
TypeScript and AWS.

> **Java 21, not 17.** 21 is the current LTS and is what is installed here;
> Spring Boot 3.5 supports both. Nothing in the project depends on the choice.

## Ports on this machine

Host `5432` and `8080` were already occupied by other containers, so:

| Service | Host port | Override with |
|---------|-----------|---------------|
| PostgreSQL | `5433` | `POSTGRES_HOST_PORT` |
| Tool Registry | `8081` | `SERVER_PORT` |

## Quick start

```bash
# 1. local config
cp .env.example .env

# 2. start PostgreSQL
docker compose -f docker/docker-compose.yml up -d

# 3. run the service (Flyway migrates on startup)
cd backend && ./mvnw spring-boot:run
```

```bash
# 4. in a second terminal - seed demo data and smoke-test
./scripts/seed-demo-data.sh

# 5. the Phase 2 scenario: three clients, three versions, then a rollback
./scripts/demo-pinning.sh
```

## Tests

```bash
cd backend

./mvnw test      # FAST lane: 44 unit + slice tests, no Docker, ~4 s
./mvnw verify    # + SLOW lane: 16 Testcontainers tests on real PostgreSQL
```

The split is deliberate: `@Tag("integration")` tests are excluded by surefire
and run by failsafe. It is the mechanism behind the pipeline optimisation in
Phase 6.

## API (Phase 1)

| Method | Path | Purpose | Codes |
|--------|------|---------|-------|
| `POST` | `/api/v1/tools` | Register a tool | 201, 409, 422 |
| `GET`  | `/api/v1/tools` | List tools (paginated) | 200 |
| `GET`  | `/api/v1/tools/{tool}` | Get one tool | 200, 404 |
| `POST` | `/api/v1/tools/{tool}/versions` | Publish an immutable version | 201, 404, 409, 422 |
| `GET`  | `/api/v1/tools/{tool}/versions` | List versions, newest first | 200, 404 |
| `GET`  | `/api/v1/tools/{tool}/versions/{version}` | **Exact** version lookup | 200, 400, 404 |
| `GET`  | `/actuator/health` | Health / liveness / readiness | 200 |

### Clients & pinning (Phase 2)

| Method | Path | Purpose | Codes |
|--------|------|---------|-------|
| `POST` | `/api/v1/clients` | Register a client | 201, 409, 422 |
| `GET`  | `/api/v1/clients` | List clients (paginated) | 200 |
| `GET`  | `/api/v1/clients/{client}` | Get one client | 200, 404 |
| `GET`  | `/api/v1/clients/{client}/tools` | Everything this client consumes | 200, 404 |
| `PUT`  | `/api/v1/clients/{client}/tools/{tool}/version` | Pin to a version, or `"latest"` | 200, 404, 422 |
| `GET`  | `/api/v1/clients/{client}/tools/{tool}/version` | **Resolve**: which version, and why | 200, 404, **410** |
| `DELETE` | `/api/v1/clients/{client}/tools/{tool}/version` | Remove the configuration | 204, 404 |

`PUT` rather than `POST`: a client has exactly one version decision per tool,
it lives at a known URL, and the operation must be idempotent so a retried
deployment cannot create duplicates.

**Rolling back is one call** — no rebuild, no artifact change:

```bash
curl -X PUT localhost:8081/api/v1/clients/client-c/tools/data-validator/version \
     -H 'Content-Type: application/json' -d '{"version":"1.2"}'
```

Errors are RFC 7807 `application/problem+json`:

```json
{
  "type": "https://platform.acme.internal/errors/not-found",
  "title": "Resource Not Found",
  "status": 404,
  "detail": "Version '999.0' of tool 'data-validator' does not exist",
  "instance": "/api/v1/tools/data-validator/versions/999.0",
  "requestId": "5b127445-6ff7-4946-921f-8bac30c65b34"
}
```

## Layout

```
internal-tool-platform/
├── backend/            Spring Boot Tool Registry Service
├── integration-tests/  Python + pytest data-driven framework   (Phase 4)
├── frontend/           small TypeScript client                 (Phase 7)
├── docker/             docker-compose.yml, Dockerfile          (Phase 7)
├── .github/workflows/  CI/CD pipeline                          (Phase 5-6)
├── scripts/            seed / smoke-test helpers
└── docs/               architecture, roadmap, interview prep
```

## Docs

- [`docs/architecture.md`](docs/architecture.md) — components, both flows, data model, container networking, secret management
- [`docs/roadmap.md`](docs/roadmap.md) — the nine phases and why they are in that order
- [`docs/interview-prep.md`](docs/interview-prep.md) — per-phase questions, debugging scenarios, and the honesty rules for describing this work
