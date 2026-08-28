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

Phases 1-7 of 9 complete — see [`docs/roadmap.md`](docs/roadmap.md).

| Phase | Deliverable | Status |
|-------|-------------|--------|
| 1 | Tool Registry Service (Spring Boot + PostgreSQL) | **done** |
| 2 | Clients & version pinning | **done** |
| 3 | Artifact distribution, checksums & promotion | **done** |
| 4 | Python data-driven pytest framework | **done** |
| 5 | Baseline CI pipeline + measurement | **done** |
| 6 | Optimised CI pipeline + before/after numbers | **done** |
| 7 | Docker image, full Compose stack, TypeScript client | **done** |
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

### Everything in containers (Phase 7)

```bash
cp .env.example .env
npm --prefix frontend install && npm --prefix frontend run build
docker compose -f docker/docker-compose.yml up -d --wait
```

| | |
|---|---|
| Dashboard | <http://localhost:3000> |
| API | <http://localhost:8081> |
| Health | <http://localhost:8081/actuator/health> |

### Or run the service on the host

```bash
cp .env.example .env
docker compose -f docker/docker-compose.yml up -d --wait postgres
cd backend && ./mvnw spring-boot:run
```

The only difference is one environment variable — `DB_URL` uses
`postgres:5432` between containers and `localhost:5433` from the host. See
[`docs/architecture.md`](docs/architecture.md) §7.

### Fetch a tool from the command line

```bash
node frontend/dist/cli.js --tool data-validator --version 1.2
node frontend/dist/cli.js --client client-a --tool data-validator   # names NO version
```

```bash
# 4. in a second terminal - seed demo data and smoke-test
./scripts/seed-demo-data.sh

# 5. the Phase 2 scenario: three clients, three versions, then a rollback
./scripts/demo-pinning.sh

# 6. the Phase 3 release pipeline: DRAFT -> upload -> promote -> download
./scripts/demo-artifacts.sh
```

Artifacts go to a local directory by default. To use real JFrog Artifactory
instead (3.8 GB image, behind a compose profile):

```bash
docker compose -f docker/docker-compose.yml --profile artifactory up -d
./scripts/setup-artifactory.sh
```

See [`docs/artifactory.md`](docs/artifactory.md).

## Tests

Three tiers, each catching what the others structurally cannot.

```bash
# Java: unit/slice (fast) and Testcontainers (slow)
cd backend
./mvnw test      # 72 tests, no Docker, ~5 s
./mvnw verify    # + 25 Testcontainers tests on real PostgreSQL, ~19 s

# Python: black-box, data-driven, against a running platform
./scripts/run-integration-tests.sh            # 75 tests, ~2 s
./scripts/run-integration-tests.sh -m smoke   # ~1 s
```

| Tier | Count | Needs | Answers |
|------|-------|-------|---------|
| Unit / slice | 72 | nothing | is the logic right? |
| Component integration | 25 | Docker | do the pieces fit? |
| Black-box API (pytest) | 75 | running platform | does it behave correctly from outside? |

The fast/slow split (`@Tag("integration")` + surefire/failsafe) is the
mechanism behind the pipeline optimisation in Phase 6.

## CI/CD

Two pipelines run on every push to `main`, on the same commit — one
deliberately unoptimised, one optimised — so the comparison is measured, not
claimed.

| Pipeline | Wall clock | Runner-seconds |
|----------|-----------:|---------------:|
| Baseline (4 runs) | mean **212 s** | 212 s |
| Optimised, warm cache (2 runs) | mean **108 s** | 142 s |
| Optimised, cold cache | 208 s | 268 s |

**Steady state: 49% faster.** On a cold cache: no faster at all — the entire
gain is a caching effect, and [`docs/ci-cd.md`](docs/ci-cd.md) §8 says so
plainly rather than quoting the flattering number alone.

The decomposition: caching and de-duplication removed **33% of the work**
(212 → 142 runner-seconds); parallelism compressed what remained by a further
**24%** (142 runner-seconds → 108 s wall).

The pipeline also **dogfoods the platform** — it registers its own build in
the registry it just built, uploads the jar, and promotes `DRAFT → PUBLISHED`.

**The black-box suite found four bugs 97 Java tests missed** — a catch-all
exception handler was turning every client mistake (malformed JSON, unknown
URL, wrong method, wrong content type) into a 500. See
[`docs/testing.md`](docs/testing.md) §5.

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

### Artifacts (Phase 3)

| Method | Path | Purpose | Codes |
|--------|------|---------|-------|
| `PUT`  | `/api/v1/tools/{tool}/versions/{v}/artifact` | Upload bytes (CI does this) | 201, 400, 409, 410 |
| `GET`  | `/api/v1/tools/{tool}/versions/{v}/artifact` | Download by exact coordinates | 200, 404, 410, **502** |
| `POST` | `/api/v1/tools/{tool}/versions/{v}/promotion` | Move the same bytes through the lifecycle | 200, 409 |
| `GET`  | `/api/v1/clients/{client}/tools/{tool}/artifact` | **The client's own copy** — no version named | 200, 404, 410, 502 |

The SHA-256 is verified on every download and returned in the `ETag` and
`X-Artifact-Sha256` headers so the client can re-verify locally.

```bash
# what CI does after a green build
curl -X PUT localhost:8081/api/v1/tools/data-validator/versions/1.2/artifact \
     -H 'Content-Type: application/octet-stream' \
     --data-binary @target/data-validator-1.2.jar

# what a consumer does - it never names a version
curl -OJ localhost:8081/api/v1/clients/client-a/tools/data-validator/artifact
```

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
├── integration-tests/  Python + pytest data-driven framework
│   ├── data/           JSON/YAML case files - the executable spec
│   ├── framework/      loader, API client, reusable assertions
│   └── tests/          one assertion path per behaviour
├── frontend/           small TypeScript client
│   └── src/            api.ts (shared contract), cli.ts, dashboard.ts
├── docker/             docker-compose.yml, Dockerfile          (Phase 7)
├── .github/workflows/  CI/CD pipeline                          (Phase 5-6)
├── scripts/            seed / smoke-test helpers
└── docs/               architecture, roadmap, interview prep
```

## Docs

- [`docs/architecture.md`](docs/architecture.md) — components, both flows, data model, container networking, secret management
- [`docs/roadmap.md`](docs/roadmap.md) — the nine phases and why they are in that order
- [`docs/ci-cd.md`](docs/ci-cd.md) — every pipeline stage explained, secret scoping, the measured baseline, and the same pipeline in Jenkins
- [`docs/testing.md`](docs/testing.md) — the three test tiers, why data-driven beats duplicated methods, fixtures and run isolation
- [`docs/artifactory.md`](docs/artifactory.md) — artifact repositories, coordinates, immutability, checksums, promotion, and the port/adapter split
- [`docs/interview-prep.md`](docs/interview-prep.md) — per-phase questions, debugging scenarios, and the honesty rules for describing this work
