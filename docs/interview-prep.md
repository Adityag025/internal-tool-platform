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

---

## Phase 2 — Clients & version pinning

### (B) What you can truthfully say you built in this learning project

- A per-client version-pinning model: `clients` + `client_tool_configuration`,
  where each client records **one explicit decision per tool** — either
  `PINNED` to an exact version or `LATEST` as a deliberate opt-in.
- The three-client scenario working end to end: client-a on 1.0, client-b on
  1.1, client-c on 2.0, simultaneously, from one registry.
- **Rollback as a single idempotent `PUT`** — no rebuild, no redeploy of the
  tool, no artifact mutated.
- Invariants pushed into PostgreSQL rather than application code:
  `pinned_version_id` is a **foreign key** (you cannot pin to a nonexistent
  version), `ON DELETE RESTRICT` (you cannot delete a version a client depends
  on), and `CHECK` constraints tying `selector` to the presence of a pinned
  version.
- A status-aware resolution path: `REVOKED` → **410 Gone**, `DEPRECATED` →
  200 with a `Deprecation` header, `PUBLISHED` → 200.
- Resolution logs the **concrete** version even in `LATEST` mode, so "which
  bytes did client-c get on that day?" stays answerable.
- 16 more tests (60 total: 44 fast, 16 integration), including a test that the
  database itself refuses to delete a pinned version.

### 5 beginner questions

1. **Why `PUT` and not `POST` for setting a client's version?**
   A client has exactly one version decision per tool and it lives at a known
   URL. `PUT` means "make the state at this URL exactly this" and is
   idempotent, so a retried deployment cannot create duplicates. `POST` implies
   appending a new subordinate resource each time.

2. **What does 410 Gone mean, and why not 404?**
   410 says the resource existed and was deliberately withdrawn. 404 says it
   was never there. A caller seeing 404 hunts for a typo; a caller seeing 410
   knows to migrate. Same rejection, completely different next action.

3. **What is a foreign key doing for you here?**
   `pinned_version_id` references `tool_versions(id)`, so the database itself
   guarantees you cannot point a client at a version that does not exist — and
   `ON DELETE RESTRICT` guarantees you cannot delete a version somebody is
   still using.

4. **What happens if a client asks for a tool it has no configuration for?**
   404, with a message naming the exact `PUT` that fixes it. Not a default to
   "latest" — an unconfigured consumer is a mistake to surface, not a gap to
   paper over.

5. **Where is `latest` allowed and where is it rejected?**
   Accepted only at *configuration* time (`PUT .../version` with
   `{"version":"latest"}`), where it is an audited decision by a named client.
   Still rejected by the registry's exact-lookup endpoint, where it would just
   be a bug.

### 5 intermediate questions

1. **Explain how rollback works in this system, and why immutability is what makes it possible.**
   `ToolVersion` rows are immutable; `ClientToolConfiguration` rows are mutable.
   Rollback is a single UPDATE of a pointer. It is *safe* only because 1.2 is
   still byte-identical to the 1.2 that was tested — so pointing back at it
   returns to a known-good state. If artifacts were mutable, "roll back to 1.2"
   would mean "rebuild something and call it 1.2", which is not a rollback.

2. **The `LATEST` selector reintroduces the floating-version problem. Why is it acceptable here?**
   Three reasons: it is opt-in per client, so the blast radius is only those
   who chose it; the choice is recorded and auditable rather than implicit; and
   resolution still produces a *concrete* version that gets logged, so the
   question "which bytes ran?" remains answerable after the fact. The danger of
   `latest` is not floating — it is floating *invisibly*.

3. **Why is the same version invariant expressed both in Java and in a CHECK constraint?**
   The Java check produces a good error message; the constraint produces a
   guarantee. Application checks are bypassed by every other write path —
   another service, a migration, a DBA's manual UPDATE. Constraints are not.
   The rule of thumb: validate in the application for UX, constrain in the
   database for truth.

4. **Your list endpoint uses `join fetch`. What breaks without it?**
   Two things. Functionally, with `open-in-view` disabled the session is closed
   by the time the controller maps entities, so touching the lazy `tool`
   association throws `LazyInitializationException`. Performance-wise, even
   with the session open it would be N+1 — one query for the configurations
   plus one per row for its tool.

5. **A version is `DEPRECATED`. Why return 200 rather than an error?**
   Deprecation is a migration signal, not a failure. Breaking existing
   consumers the moment you deprecate would make teams refuse to ever mark
   anything deprecated. So: serve it, and signal it out-of-band via the
   `Deprecation` header so monitoring can alert without parsing the body.

### 3 debugging scenarios

1. **"client-a was pinned to 1.0 but is suddenly getting 3.0."**
   Check `selector` on its configuration row first — the most likely cause is
   someone set it to `LATEST`. That is exactly why the resolve response returns
   `selector` alongside `resolvedVersion`, and why config changes log
   `from=[...] to=[...]`. Grep for `client.config.changed client=client-a`.

2. **"A deploy fails at runtime with 404 on the version endpoint, but the version exists in the registry."**
   Distinguish the two 404s — they have different `detail` text. "Version …
   does not exist" means a bad version. "Client … has no configuration for
   tool …" means the *client* was never configured. The second is common right
   after adding a new consumer, and it fails at deploy time rather than at
   configuration time, which is the argument for validating pins up front.

3. **"Deleting an obsolete version fails with a constraint violation."**
   `ON DELETE RESTRICT` is doing its job: a client is still pinned to it. The
   correct sequence is to find dependants, migrate them to a supported version,
   then delete — or, better, mark the version `DEPRECATED` (and later
   `REVOKED`) instead of deleting it at all. Deleting artifacts destroys
   reproducibility of every past build that used them.

### 30-second explanation (updated for Phase 2)

"It's an internal tool distribution platform. Each consuming team registers one
explicit decision per tool — pinned to an exact version, or opted in to latest —
and the platform resolves that to concrete artifact coordinates. Published
versions are immutable, so rolling a team back is a single pointer update to a
build that is still byte-identical to the one that was tested. Publishing a new
release moves nobody who did not ask to move."

### 2-minute explanation (the Phase 2 half)

"Phase 1 answered what *exists*. Phase 2 answers what each consumer *uses*, and
keeping those separate is the important design decision.

The pinning table holds one row per (client, tool) with a selector — PINNED or
LATEST — and a foreign key to the exact version. Three constraints do the heavy
lifting: the FK means you cannot pin to a version that does not exist;
ON DELETE RESTRICT means you cannot delete a version someone depends on; and
CHECK constraints keep the selector and the pinned version consistent. Those
are database guarantees, not conventions, so they hold no matter who writes.

The asymmetry between the two tables is the whole mechanism. Version rows are
immutable, configuration rows are mutable. Releasing is an INSERT that moves
nobody. Adopting is an UPDATE by one team. Rolling back is the same UPDATE in
reverse — and it is trustworthy precisely because the old artifact was never
mutated.

LATEST does exist, but it is opt-in per client, it is recorded, and it still
resolves to a concrete logged version. The problem with 'latest' was never
floating — it was floating invisibly."
