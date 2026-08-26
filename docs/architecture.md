# Architecture

## 1. The business problem

Three internal teams depend on the same internal tool, `data-validator`,
but they cannot all move at the same speed:

| Client   | Needs                | Why |
|----------|----------------------|-----|
| Client A | `data-validator 1.0` | Frozen for an audit; nothing may change |
| Client B | `data-validator 1.5` | Mid-migration, needs a specific bug fix |
| Client C | `data-validator 2.0` | On the newest schema format |

A distribution system that only offers "latest" cannot serve these three at
once. The platform therefore treats **(tool, version)** as the unit of
distribution, and every consumer states the exact version it wants.

## 2. Components

| # | Component | Technology | Responsibility |
|---|-----------|------------|----------------|
| 1 | **Tool Registry Service** | Java 21 + Spring Boot 3.5 | System of record for tools, versions, artifact coordinates, checksums, status |
| 2 | **Metadata store** | PostgreSQL 16 | Persists the registry; enforces uniqueness/immutability |
| 3 | **Artifact repository** | JFrog Artifactory (Docker) | Stores the actual bytes (`.jar`, `.whl`, ...) |
| 4 | **Integration test framework** | Python 3 + pytest | Data-driven black-box tests over the REST API |
| 5 | **CI/CD pipeline** | GitHub Actions | Build → test → package → publish → deploy |
| 6 | **Client** | TypeScript (small) | Picks tool + version, requests it |
| 7 | **Runtime** | Docker → AWS ECR → ECS | Where the service runs |

The registry stores **metadata and coordinates**; Artifactory stores **bytes**.
Keeping these separate is deliberate: metadata queries ("which versions exist,
which are deprecated, who uses what") are cheap relational queries, while byte
storage is a different problem with different scaling characteristics.

## 3. Build-time flow (CI/CD)

```
Developer
   |  git push
   v
GitHub Actions
   |
   +-- Build (Maven)
   +-- Unit tests            (fast lane, seconds)
   +-- Integration tests     (slow lane, real Postgres)
   +-- Package
   +-- Publish versioned artifact  --> Artifactory
   +-- Build & push Docker image   --> ECR
   v
Deploy (ECS)
```

## 4. Runtime flow (dynamic version loading)

```
Client: "give me data-validator 1.2"
   |
   v
Tool Registry Service
   |-- 1. Is the tool registered?          -> no  => 404
   |-- 2. Is "1.2" a well-formed version?  -> no  => 400
   |-- 3. Does 1.2 exist for this tool?    -> no  => 404   (NEVER falls back to latest)
   |-- 4. Is it PUBLISHED (not REVOKED)?   -> no  => 410
   |-- 5. Look up artifact coordinates in PostgreSQL
   |-- 6. Stream the bytes from Artifactory
   v
Exactly the requested artifact + its SHA-256
```

Steps 1-3 and 5 exist as of Phase 1. Steps 4 and 6 arrive in Phase 3.

## 5. Data model

```
tools                          tool_versions
-----                          -------------
id            PK               id              PK
name          UNIQUE  1 ---- * tool_id         FK -> tools.id
description                    version                 e.g. "1.2"
created_at                     major/minor/patch_part  parsed, for numeric ordering
                               artifact_path           e.g. data-validator/1.2/data-validator-1.2.jar
                               checksum_sha256
                               status                  DRAFT|PUBLISHED|DEPRECATED|REVOKED
                               created_at
                               UNIQUE (tool_id, version)   <-- immutability
```

```
clients                        client_tool_configuration
-------                        -------------------------
id           PK                id                 PK
name         UNIQUE  1 ---- *  client_id          FK -> clients.id       (CASCADE)
description                    tool_id            FK -> tools.id         (CASCADE)
created_at                     selector           PINNED | LATEST
                               pinned_version_id  FK -> tool_versions.id (RESTRICT)
                               updated_at
                               UNIQUE (client_id, tool_id)
                               CHECK  (PINNED => pinned_version_id IS NOT NULL)
                               CHECK  (LATEST => pinned_version_id IS NULL)
```

Three things the database enforces here that application code would otherwise
have to re-check on every write path:

- **`pinned_version_id` is a foreign key, not a version string.** You cannot pin
  a client to a version that does not exist.
- **`ON DELETE RESTRICT`.** You cannot delete a version that a client still
  depends on. (Contrast with `tool_versions.tool_id`, which cascades: deleting
  a tool is meant to take its versions with it.)
- **The selector CHECK constraints.** `PINNED` must name a version and `LATEST`
  must not. The invariant cannot drift, even via a manual SQL update.

### Why store the version explicitly instead of always using "latest"?

1. **Reproducibility** – A build recorded as "used data-validator 1.2" can be
   reproduced next year. "Used latest" cannot be reproduced at all, because
   "latest" is a moving target.
2. **Compatibility** – 2.0 may be a breaking change. Client A on 1.0 must not
   be silently upgraded into a broken state by someone else's release.
3. **Rollback** – Rolling back means re-pointing a client from 2.0 to 1.2.
   That is only possible if 1.2 still exists as an addressable, unchanged
   artifact.
4. **Debuggability** – "It broke on Tuesday" is answerable when the version in
   use is recorded. With `latest`, the artifact under a client changes without
   any change in that client's own repository.
5. **Blast radius** – With pinning, publishing a bad 2.0 affects nobody until
   a client explicitly opts in. With `latest`, it affects everyone at once.

`latest` is not banned - it is *opt-in*, and even then it is resolved to a
concrete version and logged, so you can always answer "which bytes actually
ran?"

### Pinning, rollback, and blast radius

A `ToolVersion` row is **immutable**. A `ClientToolConfiguration` row is
**mutable by design**. That asymmetry is the whole mechanism:

| Operation | What actually changes |
|-----------|-----------------------|
| Release 3.0 | One INSERT into `tool_versions`. No consumer moves. |
| Adopt 3.0 | One UPDATE of that client's `pinned_version_id`. |
| **Roll back** | One UPDATE, pointing back at 1.2. No rebuild, no redeploy of the tool, no artifact touched. |

Rollback is cheap *because* artifacts are immutable: 1.2 is still byte-identical
to the 1.2 that was tested, so pointing back at it is a known-good state rather
than a hope. In a mutable-artifact world, "roll back to 1.2" means "rebuild
something and call it 1.2", which is not a rollback at all.

Blast radius follows from the same asymmetry. Publishing a broken 3.0 affects
exactly the clients that opted in - which is normally none, until someone
deliberately moves.

### The status lifecycle in resolution

| Status | Resolution result |
|--------|-------------------|
| `PUBLISHED` | 200 |
| `DEPRECATED` | 200 + `Deprecation: true` header + `deprecated: true` in the body |
| `REVOKED` | **410 Gone** |
| `DRAFT` | resolvable only by explicit pin (a promotion gate arrives in Phase 3) |

410 rather than 404 for a revoked version is deliberate: 404 tells a caller
"you made a typo", 410 tells them "this existed and was withdrawn - you must
move". Different diagnosis, different fix.

### Why parse the version into major/minor/patch columns?

String ordering is wrong for versions: lexicographically `"1.10" < "1.9"`, but
1.10 is the *newer* release. Sorting, "latest", and range queries all need
numeric parts. The raw string is kept as well, so `1.0` never silently becomes
`1.0.0` - the version string is part of the artifact's identity.

## 6. Layering inside the service

```
web/          HTTP only: status codes, JSON shapes, problem+json errors
service/      business rules: immutability, exact resolution, validation
repository/   Spring Data JPA interfaces
domain/       entities + the SemanticVersion value object
config/       cross-cutting: request id, access logging
```

Rules live in `service/`, never in controllers, so the same rules apply
whether the caller is REST today or a CLI/queue consumer tomorrow.

## 7. Container networking

Inside a Docker Compose network, containers reach each other by **service
name** on the container's own port:

```
jdbc:postgresql://postgres:5432/toolplatform     <-- correct between containers
jdbc:postgresql://localhost:5432/toolplatform    <-- WRONG between containers
```

`localhost` inside a container means *that container's own loopback*. The
Spring Boot container has no PostgreSQL listening on its own loopback, so the
connection is refused. Docker's embedded DNS resolves the service name
`postgres` to the container's current IP - which is essential because that IP
changes on every restart.

**Published ports are a different thing.** `"5433:5432"` publishes the
container's 5432 onto the *host's* 5433. That mapping only matters for
processes on the host. In Phase 1 the app runs on the host, so it correctly
uses `localhost:5433`. From Phase 7 the app moves into Compose and switches to
`postgres:5432`.

> Port note for this machine: host 5432 and 8080 were already taken by other
> containers, so this project uses **5433** (Postgres) and **8081** (app).
> Both are environment-overridable.

## 8. Configuration and secrets

No credential is ever written in source. Every value comes from an environment
variable with a local-dev default:

```yaml
url: ${DB_URL:jdbc:postgresql://localhost:5433/toolplatform}
password: ${DB_PASSWORD:localdev}
```

| Environment | Source of secrets |
|-------------|-------------------|
| Local       | `.env` (gitignored), `.env.example` is the committed template |
| CI          | GitHub Actions Secrets → env vars for the step that needs them |
| AWS         | SSM Parameter Store / Secrets Manager, injected by the ECS task definition |

The application code is identical in all three; only the injection mechanism
changes. That is the point of externalised configuration.
