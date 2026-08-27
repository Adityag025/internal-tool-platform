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

---

## Phase 3 — Artifact distribution, checksums & promotion

### (B) What you can truthfully say you built in this learning project

- An `ArtifactStore` **port** with two adapters — a filesystem one for
  development/CI and a **JFrog Artifactory** one over its REST API — selected
  at startup by configuration, with nothing in the service layer aware of which.
- Real byte distribution: `PUT .../artifact` to upload (what CI does after a
  green build), `GET .../artifact` to download, and a client-facing
  `GET /clients/{c}/tools/{t}/artifact` where **the consumer never names a version**.
- **SHA-256 integrity end to end**: computed at upload, sent to Artifactory in
  `X-Checksum-Sha256` so it verifies on receipt, stored in the registry,
  re-verified on every download, and returned in the `ETag` and
  `X-Artifact-Sha256` headers so the client can check independently.
- **Immutability at three layers** — DB unique constraint, a write-once
  `sealWith()` on the domain object, and stores that refuse to overwrite.
- A **promotion state machine** (`DRAFT → PUBLISHED ↔ DEPRECATED → REVOKED`,
  `REVOKED` terminal) that moves the same bytes without rebuilding, with a
  release gate that refuses to publish a version having no artifact.
- Correct failure semantics: **502** for registry/store drift and checksum
  mismatch (our inconsistency), **410** for revoked, **409** for immutability
  and illegal promotions, **404** only for genuinely unknown coordinates.
- A protocol-level test of the Artifactory adapter against a mock HTTP server
  that **caught a real path-encoding bug** before any container ran.
- 96 tests total (71 fast, 25 integration).

### (C) Still architectural example only

The CI pipeline, AWS deployment, and the Python data-driven test framework.
Also: a **live** Artifactory was configured but did not boot reliably on this
machine (see `docs/artifactory.md` §10). Say that plainly — "I integrated
against the Artifactory REST API and verified the adapter against a mock
server; I did not run a production Artifactory instance" is both honest and
more impressive than a vague claim.

### 5 beginner questions

1. **What is an artifact repository, and why not just use git?**
   Git stores diffs of text and makes clones enormous when you commit binaries.
   An artifact repository is purpose-built for build outputs: unique
   coordinates, immutability, checksums, and lifecycle policies.

2. **What are artifact coordinates?**
   The unique address of exactly one build. `com.acme:data-validator:1.2` in
   Maven, `registry/tools/data-validator:1.2` in Docker,
   `internal-tools-local/data-validator/1.2/data-validator-1.2.jar` here.

3. **Why does every artifact have a SHA-256?**
   So you can prove the bytes you received are the bytes that were published.
   It is checked on upload, on download, and by the consumer.

4. **What does "immutable artifact" mean?**
   A published coordinate never changes content. Wanting different bytes means
   wanting a new version.

5. **What is artifact promotion?**
   Moving the *same* bytes through a lifecycle — draft, published, deprecated,
   revoked — by changing a label, never by rebuilding.

### 5 intermediate questions

1. **Why promote instead of rebuilding for each environment?**
   A rebuild produces different bytes: different timestamps, possibly a
   different transitive dependency, a different compiler patch. So "the thing
   we tested in staging" and "the thing we released to production" would not
   be the same artifact, and every test result recorded before the rebuild
   would be worthless. Promotion is what makes *build once, deploy everywhere*
   true rather than aspirational.

2. **Why is a missing artifact 502 and not 404?**
   404 means the caller asked for something that does not exist. Here the
   registry says the version exists and the store disagrees — the platform is
   internally inconsistent. Returning 404 sends the consumer hunting for a typo
   when the fix is on our side. 502 says "the system behind me failed", which
   also tells the caller that retrying is reasonable.

3. **You have an interface with two implementations. Justify it.**
   Three concrete payoffs, not architectural taste. Integration tests exercise
   the whole distribution path against a temp directory, so they need no
   Artifactory. CI does not go red because an external service is down. And
   swapping to S3 or Nexus is a new adapter rather than a refactor. The cost is
   one interface and one config property.

4. **How do you test an integration with an external service you cannot run?**
   Against a mock HTTP server, asserting the exact request produced — method,
   URL, headers, body — and how each response class is interpreted. A live
   instance would mostly be testing the vendor's code. In this project that
   approach caught a genuine bug: passing a multi-segment path as a URI
   template variable percent-encodes its slashes to `%2F`, collapsing the
   repository layout. Found offline in milliseconds.

5. **Where exactly is immutability enforced, and why in more than one place?**
   `UNIQUE (tool_id, version)` in PostgreSQL stops two concurrent publishes.
   `ToolVersion.sealWith()` is write-once and stops re-uploading bytes for a
   sealed version. The stores refuse to overwrite an existing path. These are
   not duplicates — each closes a hole the others do not, because each layer
   is reachable by a different route.

### 3 debugging scenarios

1. **"Downloads started failing with 502 checksum-mismatch overnight."**
   The bytes in the store no longer hash to what the registry recorded. Someone
   or something wrote to the store out of band — a manual "fix", a restored
   backup, a sync job, or genuine corruption. Do **not** update the recorded
   checksum to make the error go away: that destroys the only evidence the
   artifact changed. Find who wrote to the path, then re-publish as a new
   version.

2. **"Artifacts upload fine but land in the wrong place in Artifactory."**
   Almost always URL encoding. Check whether the multi-segment path is being
   sent as a single template variable — `%2F` in the request URL is the
   giveaway. A protocol-level test that asserts on the exact URI catches this;
   an end-to-end test only shows a confusing 404 much later.

3. **"CI publishes a version, then the deploy says the artifact does not exist."**
   Registry and store are out of step. Either the metadata row was created but
   the upload step failed and the pipeline did not fail with it, or they point
   at different environments. Check `/actuator/health` for which store the
   service actually selected — it is logged at startup as
   `artifact.store.selected` — and make the pipeline's publish step fail loudly
   rather than continue past a failed upload.

### 30-second explanation (updated)

"It's an internal tool distribution platform. CI publishes an immutable
versioned artifact with a SHA-256; consumers pin to an exact version and
download it without ever naming a version number in their own code. The
platform verifies the checksum on every download and refuses to serve bytes
that do not match what was published. Releases move through a promotion
lifecycle — the same bytes get relabelled, never rebuilt — so what was tested
is provably what ships."

### 2-minute explanation (the Phase 3 half)

"Phase 3 is where it stops handling coordinates and starts handling bytes.

There's an ArtifactStore interface with two implementations — a filesystem one
and a JFrog Artifactory one over its REST API. That is not abstraction for its
own sake: it means the integration tests exercise the entire upload, checksum,
promote, download path without Artifactory running, so CI does not depend on an
external service being up.

Integrity runs end to end. The SHA-256 is computed at upload and sent to
Artifactory in a header so it verifies on receipt; it is stored in the registry;
it is re-verified against the actual bytes on every download; and it goes back
to the client in the ETag so they can check independently. A mismatch is a 502
and the bytes are not served — serving 'probably fine' bytes defeats the entire
point of having a checksum.

Promotion is the release mechanism. DRAFT to PUBLISHED is a gate that refuses a
version with no artifact. PUBLISHED and DEPRECATED move back and forth. REVOKED
is terminal, because un-revoking would let a consumer who correctly stopped
using an artifact be silently handed it again. Nothing in that flow rebuilds
anything — the checksum is identical before and after, and there's a test that
asserts exactly that.

The part I'd actually call out in an interview is the adapter test. I tested
the Artifactory integration against a mock HTTP server rather than a live
instance, and it immediately caught a bug: passing the artifact path as a URI
template variable percent-encodes the slashes, so the whole repository layout
would have collapsed into the root. Milliseconds, offline, before a container
ever started."

---

## Phase 4 — Data-driven integration testing

### (B) What you can truthfully say you built in this learning project

- A **reusable pytest framework** for black-box API testing: a loader that
  turns JSON/YAML case files into parameterised tests, a typed API client that
  never asserts, and an assertion library whose failures print the request id,
  status, latency and body.
- **49 of the 75 tests are data-driven** from four case files — 15 version
  lookups, 9 client resolutions, 9 promotion transitions, 16 malformed-input
  cases. Adding a case is a data edit, not a code edit.
- **Run-scoped isolation**: each run namespaces its tool and clients with a
  random id, so the suite is re-runnable against a server holding immutable
  artifacts, and two runs can execute concurrently without colliding.
- Negative coverage for every edge case that matters: path traversal in names
  and artifact paths, malformed and corrupted checksums, `latest` as a lookup
  key, duplicate publishes, registry/store drift, revoked artifacts,
  malformed JSON, wrong method, wrong content type, unknown endpoints.
- JUnit XML + self-contained HTML reports, and pytest markers (`smoke`,
  `negative`, `artifact`, `slow`) so CI can tier its runs.
- **It found four real bugs that 97 Java tests missed** — and I fixed them.

### (C) Still architectural example only

The CI pipeline (Phases 5-6) and AWS deployment (Phase 8).

---

### The story to actually tell in an interview

This is the strongest single anecdote in the project. Learn it.

> "I had 97 Java tests passing — unit, Spring slice, and Testcontainers
> integration tests. Then I wrote a black-box Python suite that talks to the
> service over HTTP with no knowledge of Spring, and on its first run five
> tests failed. Four were the same bug.
>
> I had written a catch-all `@ExceptionHandler(Exception.class)`. That is more
> specific than nothing, so it outranks Spring's own
> `DefaultHandlerExceptionResolver` — and it was silently swallowing the
> framework's exceptions. Malformed JSON returned 500 instead of 400. An
> unknown URL returned 500 instead of 404. Wrong HTTP method, 500 instead of
> 405. Wrong content type, 500 instead of 415.
>
> Every client mistake was being reported as a server fault. That pages the
> on-call engineer for somebody's typo, it makes 5xx alerting useless because
> the baseline is full of noise, and it tells the caller to retry when
> retrying can never help.
>
> The Java tests structurally could not have caught it. They only ever sent
> requests the controllers were written to accept. You have to be outside the
> application to send a request it has no handler for. The fix was to extend
> `ResponseEntityExceptionHandler` so Spring's handlers get their precedence
> back, while an overridden `handleExceptionInternal` keeps everything in
> problem+json shape."

Why it lands: it is specific, it names a real mechanism, it shows you
understand *why* test tiers are not redundant, and it ends with a fix rather
than a complaint.

---

### 5 beginner questions

1. **What is data-driven testing?**
   The test logic is written once; the cases live in a data file. One
   parameterised function covers fifteen inputs instead of fifteen functions.

2. **What is a pytest fixture?**
   A reusable piece of setup, requested by naming it as a test argument.
   `scope="session"` means it is built once for the whole run — which is why
   seeding ~30 HTTP calls costs the suite one second, not one second per test.

3. **What is `conftest.py`?**
   Where pytest looks for fixtures and hooks shared across a directory. No
   import needed; tests just name the fixture.

4. **Why pin dependencies exactly instead of `>=`?**
   Same reason artifacts are immutable: a test run you cannot reproduce cannot
   be trusted. `>=` means a library release can turn your suite red — or,
   worse, green — with no change on your side.

5. **What is the difference between a unit test and an integration test here?**
   The unit test knows the internals and mocks the collaborators; it is fast
   and precise. The integration test uses real HTTP and a real database; it is
   slower and proves the pieces actually fit together.

### 5 intermediate questions

1. **You already had 97 Java tests. Justify a second suite in another language.**
   Not "more tests is better" — a test that shares the application's
   assumptions cannot find a bug in those assumptions. The Java tests ran
   inside Spring's object graph and only ever sent requests the controllers
   were written for. The black-box suite found four bugs in the first run,
   all of them in requests no controller existed for. The language being
   different is incidental; the *vantage point* is the point.

2. **How do you keep an integration suite re-runnable against immutable data?**
   Every run namespaces what it creates with a random id. The data files still
   use a logical name for readability, and a fixture maps it to the real
   run-scoped one. This also makes concurrent runs safe, which matters as soon
   as CI builds several branches at once.

3. **When is data-driven the WRONG approach?**
   When the test is a *sequence* rather than the same question with different
   inputs. "Observe, publish a new version, observe again" cannot be expressed
   as a row in a table without inventing a mini-language in your data file. At
   that point write a plain test function.

4. **How do you stop data-driven expectations from becoming flaky?**
   Assert the invariant, not a snapshot. One case here asserted that a
   floating client resolves to `"2.0"` — and it failed, because another test
   legitimately published something newer and a floating client is supposed to
   move. The literal was flaky by construction. It was replaced with a
   sentinel resolved at run time, so the case asserts "latest equals the
   newest published version" rather than today's answer to that question.

5. **Why assert on the error `type` URI rather than the message text?**
   The `type` is a stable, documented identifier; `detail` is prose someone
   will reword next sprint. Asserting on prose produces a suite that breaks
   for reasons that are not bugs — which trains people to ignore failures.

### 3 debugging scenarios

1. **"The suite passes locally and fails in CI."**
   Start with the differences the suite itself controls: is `BASE_URL`
   pointing at the same build? Are the pinned dependencies actually installed,
   or did CI resolve something newer? Is another job running against the same
   server? The run-scoped namespacing exists precisely so the last one is not
   the answer — but verify it, because a shared-state collision looks exactly
   like a real bug.

2. **"One parameterised case fails intermittently."**
   Almost always a hidden dependency on other tests' side effects. Run it
   alone (`pytest -k <case-id>`); if it passes in isolation, the expectation
   is coupled to global state. Fix the *expectation* to assert an invariant,
   not the ordering — reordering tests to make it pass just hides it.

3. **"A test fails in CI and the log is not enough to diagnose it."**
   That is a defect in the assertion, not in the test. Every failure message
   should carry enough to act on: the full URL, the status, the response body,
   and the correlation id you can grep for in the service log. If you had to
   re-run it locally to find out what happened, improve the assertion helper —
   once, for all the cases that use it.

### 30-second explanation

"I built a data-driven integration test framework in Python. The test logic is
written once; the cases live in JSON and YAML files that anyone can read and
extend — about fifty of the seventy-five tests come from four data files.
It talks to the service purely over HTTP, so it tests it the way a real client
would, and on its first run it found four bugs that ninety-seven Java tests
had missed."

### 2-minute explanation

"The suite has three layers. `framework/` holds the reusable machinery: a
loader that turns JSON or YAML into parameterised pytest cases with readable
ids, an API client that wraps requests and deliberately never asserts — a 4xx
is a valid expected outcome — and an assertion library where every failure
prints the URL, status, latency, body, and the correlation id you can grep for
in the service log. `data/` holds the cases. `tests/` holds one well-reviewed
assertion path per behaviour.

The argument for data-driven is really an argument about what happens over
time. Fifteen near-identical test methods means adding a case costs code, so
people stop adding cases; improving an assertion means editing fifteen copies,
so nobody improves it; and a missing case looks exactly like the others —
absent. With a table, a missing row in a state machine is visible at a glance.
The promotion transitions are the clearest example: a state machine is
naturally a table.

The hard part was making it re-runnable. Published artifacts are immutable and
there is no delete endpoint, so a suite that hard-coded the tool name would
pass once and then 409 forever. Every run namespaces its own tool and clients
with a random id, which also means concurrent CI runs cannot collide.

And it earned its keep immediately. Ninety-seven Java tests were green, and
the black-box suite failed five tests on the first run. Four were one bug: a
catch-all exception handler was outranking Spring's own resolver and turning
every client mistake into a 500 — malformed JSON, unknown URL, wrong method,
wrong content type. That is the kind of bug an in-process test cannot find,
because it only ever sends requests the application was written to accept."

---

## Phase 5 — Baseline CI pipeline and measurement

### (B) What you can truthfully say you built in this learning project

- A complete GitHub Actions pipeline: checkout, JDK setup, unit/slice tests,
  Testcontainers integration tests, packaging, CI-derived versioning, starting
  real dependencies, black-box pytest against a running instance, artifact
  publishing, Docker image build, and report upload — with any non-zero exit
  failing the build.
- A pipeline that **dogfoods the platform**: it registers its own build in the
  registry it just built, uploads the 55 MB jar, and promotes
  `DRAFT → PUBLISHED`, so the release gate is exercised on every run.
- **Secret handling scoped to a single step**, driven by GitHub Actions
  Secrets, never written to disk or argv, degrading to a skip-with-notice when
  unconfigured.
- A **measured baseline over repeated runs** — 201 s and 250 s, mean ~225 s —
  with a per-step breakdown, rather than a single number or an estimate.
- Two real bugs the pipeline caught that were invisible locally.

### (C) Still architectural example only

The optimised pipeline (Phase 6) and AWS deployment (Phase 8). Do not claim a
percentage improvement yet — there isn't one until Phase 6 measures it.

---

### The two bugs, and why they matter

Both are worth telling because they are the honest answer to "what is a
pipeline actually for?"

**1. `docker compose` interpolates the entire file before applying profiles.**
`${ARTIFACTORY_MASTER_KEY:?...}` failed `docker compose up postgres`, a service
that has nothing to do with Artifactory. It worked locally only because a
`.env` file existed on my machine and not in the repo.

**2. An unanchored `.gitignore` pattern hid the whole test suite's data.**
`data/` matches a directory of that name at *any* depth. It was meant for the
local artifact store; it also matched `integration-tests/data/`, so all four
case files were never committed. The suite was 75/75 green locally against
files CI could not see. First run: `FileNotFoundError` on every one.

The lesson in one line: **a pipeline's first job is to run your code somewhere
that is not your machine.** Both bugs were pure environment drift, and neither
was findable by any test.

---

### 5 beginner questions

1. **What is CI, and what is CD?**
   Continuous Integration: every commit is automatically built and tested
   against the mainline, so integration problems surface in minutes rather
   than at a merge weeks later. Continuous Delivery: every green build
   produces a deployable artifact. Continuous Deployment goes one step
   further and releases it automatically.

2. **Why does the pipeline generate the version number?**
   `github.run_number` is monotonic and unique, so every green build gets
   coordinates that cannot collide with a previous one. Humans forget to bump
   versions; CI cannot. It is what makes the registry's immutability rule
   enforceable instead of aspirational.

3. **What happens when a step fails?**
   Any non-zero exit code fails the job and every later step is skipped. That
   is the correct default — a pipeline that continues past a failure is just
   an expensive log file.

4. **Why upload test reports with `if: always()`?**
   Because you need the reports most when the run failed, and the default is
   to skip subsequent steps on failure.

5. **Where do the credentials live?**
   In GitHub Actions Secrets, injected into the environment of the single step
   that needs them. Never in a file, never echoed, never as a command-line
   argument — argv is readable by other processes on the machine.

### 5 intermediate questions

1. **How do you know a pipeline is slow, and where?**
   You measure it per step, more than once. In this project the intuitive
   answer would have been "add caching" — but the largest single cost was
   **85 seconds of fixed `sleep`**, 38% of the run, waiting for services that
   were ready in about ten. Caching was the second-biggest win, not the first.
   Measure before optimising, or you optimise the thing you happened to think
   of.

2. **Why take two baseline samples instead of one?**
   Because the two runs differed by 49 seconds — a 24% spread on identical
   input. All of it was in network-bound steps. A single "before" number would
   have let me quote whatever improvement I liked afterwards. The honest
   comparison is mean-to-mean on the same runner class.

3. **Your pipeline compiles the application four times. Why is that bad beyond speed?**
   Time is the least of it. Four builds means four chances to produce
   *different bytes* — a different transitive dependency resolved, a different
   base image patch. The artifact you tested is then not provably the artifact
   you shipped, which quietly invalidates every test result before it. "Build
   once, deploy everywhere" is a correctness rule, not a performance tip.

4. **How would you implement this in Jenkins, and what actually changes?**
   The stages map one to one; `withCredentials` replaces `secrets.*`,
   `stash`/`unstash` replaces artifact upload/download, `parallel` replaces
   separate jobs. The real difference is the execution model: Jenkins agents
   are usually long-lived, so the Maven cache is "free" — and that hides
   state. A stale `~/.m2` on an agent is a classic source of "only fails on
   the build server". Actions runners are destroyed after every run, so
   caching is explicit and reproducibility is the default.

5. **Fixed `sleep` versus polling — argue it properly.**
   A fixed sleep is wrong in both directions simultaneously. Too short and it
   is flaky; too long and it is wasteful; and on a loaded runner it is both on
   different days. Polling a readiness endpoint returns as soon as the service
   is actually ready and fails fast with a real error if it never is. The
   health endpoint exists precisely to answer "are you ready?" — a sleep
   ignores it and guesses instead.

### 3 debugging scenarios

1. **"It passes locally and fails in CI."**
   Assume environment drift before assuming a code bug, and check what the two
   environments do *not* share: uncommitted files (both of this project's CI
   bugs were this), environment variables that exist only in your shell,
   installed tools, and a dirty local build directory. `git status --ignored`
   and a fresh clone into a temp directory find most of it in a minute.

2. **"CI is flaky — it passes on re-run."**
   Suspect timing and shared state first. Fixed sleeps that are sometimes long
   enough, tests that depend on execution order, or two concurrent runs
   sharing one server. Do not "fix" it with a retry: a retry converts a
   reproducible bug into an intermittent one you will chase for months.

3. **"The pipeline went from 4 minutes to 20 and nobody changed it."**
   Nobody changed the pipeline; something it depends on changed. A cache key
   that no longer matches, so every run is a cold start. A new transitive
   dependency. A base image that grew. Compare per-step timings against an
   older run — which requires that you kept the old numbers, which is why they
   are written down in `docs/ci-cd.md`.

### 30-second explanation

"I built the pipeline twice on purpose. The first version is deliberately
unoptimised, and I measured it over repeated runs — 201 and 250 seconds — with
a per-step breakdown. That's the 'before'. The measurement itself was the
interesting part: 38% of the run was fixed `sleep` statements waiting for
services that were ready in a fraction of the time, which is not the answer
I'd have guessed. Phase two of the work fixes them and measures the difference
on the same commit."

### 2-minute explanation

"The pipeline runs on every push: unit and slice tests, Testcontainers
integration tests against a real PostgreSQL, packaging, then it starts the
application it just built and runs the black-box pytest suite against it over
real HTTP. Then it publishes. The version number comes from the CI run number,
so every green build gets coordinates that can't collide — which is what makes
the registry's immutability rule enforceable rather than a convention.

There's a detail I like: the pipeline dogfoods the platform. It registers its
own build in the registry it just built, uploads the jar, and promotes it from
DRAFT to PUBLISHED. So if the release gate ever regressed, the pipeline itself
would be the thing that noticed.

I wrote the baseline deliberately unoptimised, because I wanted a real 'before'
number rather than a guess. Two runs on the same commit came out at 201 and 250
seconds — and that 24% spread was itself a finding, because all of it was in
network-bound steps that caching removes. The biggest single cost wasn't what I
expected: 85 seconds of fixed sleeps, 38% of the run, waiting for a database
that was ready in three seconds and an app that was up in eight.

And the pipeline immediately earned its keep by finding two bugs that were
invisible on my machine. One was a Compose file using the required-variable
syntax, which fails the whole command even for services you aren't starting.
The other was an unanchored gitignore pattern — `data/` matched at any depth,
so the entire data-driven test suite's case files were never committed. The
suite was green locally against files CI couldn't see. That's the real answer
to what a pipeline is for: running your code somewhere that isn't your laptop."
