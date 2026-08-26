# Interview preparation

> **Honesty rule, applied throughout this document.**
> This is a **learning/reconstruction project**. It is *inspired by* the kinds
> of responsibilities on your resume; it is **not** a reimplementation of any
> employer's internal system, and you must never describe it as one.
>
> Three separate things, always kept apart:
> - **(A) Resume claim** — what you actually did at work. Only you can state this.
> - **(B) Demonstrated here** — code in this repo you can open and run in an interview.
> - **(C) Architectural example** — designs discussed in docs but not built.
>
> Safe framing: *"At work I was responsible for X. To deepen my understanding
> of the underlying mechanics I also built a personal project that implements
> Y — here is the repository."*

---

## Phase 1 — Tool Registry Service

### (B) What you can truthfully say you built in this learning project

- A Spring Boot 3.5 / Java 21 REST service that is the system of record for
  internal tools and their released versions.
- A PostgreSQL schema managed by **Flyway migrations**, with a
  `UNIQUE (tool_id, version)` constraint that makes published versions
  **immutable at the database level**.
- **Exact-version resolution** that returns 404 for a version that does not
  exist and never silently falls back to `latest`.
- A `SemanticVersion` value object that parses `MAJOR.MINOR[.PATCH]` and
  orders versions **numerically** (so 1.10 correctly sorts above 1.9).
- **RFC 7807 `application/problem+json`** error responses with stable `type`
  URIs, so automated clients assert on machine-readable fields.
- A **two-tier test suite**: 28 fast unit/slice tests (~5 s, no Docker) and 6
  Testcontainers-backed integration tests against real PostgreSQL — split via
  JUnit tags and Maven surefire/failsafe. This split is the foundation of the
  pipeline optimisation in Phase 6.
- Request-correlation logging (`X-Request-Id` in MDC) with per-request
  latency, plus Spring Boot Actuator health/liveness/readiness probes.

### (C) Architectural example only, at this stage

Artifactory integration, client pinning, the CI pipeline, AWS deployment.
Do not claim these yet — they are Phases 2-8.

---

### 5 beginner questions

1. **What is the difference between `@Controller` and `@RestController`?**
   `@RestController` = `@Controller` + `@ResponseBody`: every handler's return
   value is serialised into the response body instead of being resolved as a
   view name.

2. **Why is the service annotated `@Transactional` and not the controller?**
   The transaction should span one unit of business work. Putting it on the
   controller would keep a database transaction open across HTTP concerns
   (serialisation, validation) and would leak persistence into the web layer.

3. **What does HTTP 404 mean here, and why not return 200 with an empty body?**
   404 means the addressed resource does not exist. Returning 200 with an
   empty body forces every client to invent its own emptiness check, and makes
   "not found" indistinguishable from "found, but empty" in logs and metrics.

4. **What is Flyway doing?**
   It applies versioned SQL migration files exactly once each, in order, and
   records them in `flyway_schema_history`. The schema becomes a reviewable,
   reproducible part of the repository instead of manual DBA steps.

5. **Why `spring.jpa.hibernate.ddl-auto=validate`?**
   It tells Hibernate to verify the entities against the existing schema and
   refuse to start on a mismatch, instead of altering the schema itself.
   Flyway owns the schema; Hibernate only checks it. *(In this project that
   setting immediately caught a real bug: `CHAR(64)` in SQL is reported as
   `bpchar` and does not match the `varchar(64)` Hibernate expects.)*

### 5 intermediate questions

1. **Where exactly is immutability enforced, and why there?**
   In PostgreSQL, via `UNIQUE (tool_id, version)`. Application-level checks
   are advisory — two concurrent publishes both pass an `exists()` check and
   both proceed. Only the database can arbitrate. The service catches the
   resulting `DataIntegrityViolationException` and translates it to 409, so
   the check-then-act race is handled correctly rather than pretended away.

2. **Why store `major/minor/patch` as integers when you already store the string?**
   Version ordering is numeric, not lexicographic: `"1.10" < "1.9"` as strings
   but 1.10 is newer. Any "latest" or range query needs integers. The raw
   string is kept so `1.0` round-trips as `1.0`, since the version string is
   part of the artifact's identity.

3. **What is `open-in-view` and why is it disabled?**
   Enabled (the Spring Boot default), it holds the persistence session open
   until the HTTP response is rendered, so lazy proxies silently load during
   serialisation — hidden N+1 queries. Disabled, the session closes with the
   service call, so lazy access fails loudly at development time and you are
   forced to be explicit about what you load. *(Concretely: `ToolVersionResponse`
   takes the tool name as a parameter instead of calling `getTool().getName()`.)*

4. **Why RFC 7807 instead of a custom error JSON?**
   It is a standard media type (`application/problem+json`) with defined
   fields (`type`, `title`, `status`, `detail`, `instance`). Clients — including
   the Phase 4 Python test framework — assert on a stable `type` URI rather
   than parsing English prose that changes with every reword.

5. **Why split tests into surefire and failsafe lanes?**
   Unit/slice tests need no Docker and finish in seconds, so they can run on
   every push and fail fast. Integration tests need a real database, take
   ~10× longer, and belong after packaging. Separating them means developers
   get feedback in seconds while still getting full verification before
   release — the core mechanism behind reducing QA cycle time.

### 3 debugging scenarios

1. **"The app won't start: `Schema-validation: wrong column type encountered`."**
   Hibernate's expectation and the migrated schema have diverged. Read the
   column named in the message, compare the SQL type with the Java field, and
   fix it in a **new** migration (`V2__…`) if V1 has already been applied
   anywhere. Never edit an applied migration — Flyway checksums it and will
   refuse to run. *This exact failure happened during Phase 1.*

2. **"`GET /api/v1/tools/data-validator/versions/1.2` returns 404 but the row is in the database."**
   Work outward: (a) is it in the right table with the exact string `1.2` and
   not `1.2.0`? (b) does the tool name in the URL match `tools.name` exactly,
   including case? (c) is the app connected to the database you are inspecting
   — check the JDBC URL in the startup log, since two Postgres instances run
   on this machine on different ports? (d) grep the access log for the
   `X-Request-Id` of the failing call.

3. **"Two CI jobs published version 1.2 at the same time; one got a 500."**
   Both passed the `exists()` check, then one hit the unique constraint. A 500
   means the `DataIntegrityViolationException` escaped instead of being mapped
   to 409. The fix is not to lock harder — it is to treat the constraint
   violation as the expected outcome of a lost race and translate it, which is
   what `ToolRegistryService.publishVersion` does.

### Simple architecture explanation

"A Spring Boot service backed by PostgreSQL is the system of record for
internal tools and their released versions. Clients ask for a tool at an exact
version; the service validates the request, looks up that version's artifact
coordinates, and returns them. Versions are immutable — enforced by a unique
constraint — and a version that does not exist returns 404 rather than
silently falling back to the newest one."

### 30-second explanation

"It's an internal tool distribution platform. Teams depend on different
versions of the same internal tool, so the platform makes every consumer
request an exact version instead of 'latest'. A Spring Boot registry stores
which versions exist, where their artifacts live, and their checksums;
published versions are immutable, so a build is reproducible months later.
I built it to understand release engineering — versioning, artifact
repositories, and CI/CD pipeline design — from the inside."

### 2-minute explanation

"The problem is that one internal tool has several consumers who cannot all
upgrade at the same time. If distribution only offers 'latest', publishing a
release silently changes every consumer at once — you can't reproduce an old
build, you can't roll back, and one bad release breaks everyone.

So the unit of distribution is (tool, version). A Spring Boot service backed
by PostgreSQL is the registry: it records each tool, each released version,
that version's coordinates in the artifact repository, its SHA-256, and its
lifecycle status. `UNIQUE (tool_id, version)` makes published versions
immutable — re-publishing 1.2 is a 409, not an overwrite — which is what makes
'we tested 1.2' a meaningful statement. Resolution is exact: asking for a
version that doesn't exist returns 404, because silently serving different
bytes than the ones requested is the worst failure a distribution system can
have.

Two details I'd call out. Versions are parsed into major/minor/patch integers,
because string ordering says 1.10 is older than 1.9. And the tests are split
into two lanes — fast unit and slice tests with no Docker, and slower
Testcontainers tests against real PostgreSQL — which is what later lets the
CI pipeline give fast feedback on every push without giving up full
verification before release.

From there the roadmap adds per-client version pinning, Artifactory for the
actual bytes, a data-driven pytest framework, and a GitHub Actions pipeline
that I first build deliberately unoptimised so I can measure the before and
after of caching and test tiering rather than quote a number I made up."
