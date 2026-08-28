# Interview question bank

Grounded in what this project actually contains. Where an answer cites a
number or a bug, it is a real one from the repository — those are the details
that make an answer sound lived-in rather than revised.

Per-phase questions (with debugging scenarios and 30-second / 2-minute
pitches) are in [`interview-prep.md`](interview-prep.md). This file is
organised by topic for last-minute revision.

---

## 1. CI/CD

**What is CI, and what is CD?**
CI: every commit is automatically built and tested against the mainline, so
integration problems surface in minutes rather than at a merge weeks later.
Continuous Delivery: every green build produces a deployable artifact.
Continuous Deployment: it is released automatically. This project does CI plus
Delivery — deployment is gated on a tag and a GitHub Environment approval.

**What should fail a build?**
Any non-zero exit: compile errors, any failing test, a failed publish. A
pipeline that continues past a failure is an expensive log file.

**Why should CI own the version number?**
`github.run_number` is monotonic and unique, so every green build gets
coordinates that cannot collide. Humans forget to bump versions; CI cannot.
It is what makes the registry's immutability rule enforceable rather than a
convention.

**What is fail-fast, and what does it cost?**
Run the cheapest, most likely-to-fail checks first. The cost is that strict
ordering serialises work. This pipeline runs the fast and slow lanes in
*parallel* instead, because on the happy path — most runs — parallel is
faster, and GitHub cancels dependent jobs when one fails, so fail-fast is
preserved where it actually saves anything.

**How do you pass a build artifact between CI jobs?**
`upload-artifact` / `download-artifact`. Jobs run on separate machines with no
shared filesystem, so this is how "build once, deploy everywhere" is achieved
on Actions. In Jenkins it is `stash`/`unstash`.

## 2. Pipeline optimisation

**How do you know a pipeline is slow, and where?**
Measure per step, more than once. Here the intuitive answer was caching — but
the measurement said **85 seconds, 38% of the run, was fixed `sleep`** waiting
for a database ready in ~3s and an app up in ~8s. Caching was second. Measure
first or you optimise the thing you happened to think of.

**Quote your improvement and defend it.**
212s → 108s mean, `((212−108)/212)×100 = 49.1%`, from four baseline runs and
two optimised runs on the same commit and runner class. And it is 49% *in
steady state only*: with caches purged the optimised pipeline is 208s against
the baseline's 212s — no improvement at all. The entire gain is a caching
effect.

**How do you attribute a saving when jobs run in parallel?**
You cannot, with wall clock alone — removing work from a job off the critical
path saves nothing. Use two measures: **runner-seconds** for work removed
(additive, unambiguous) and **wall clock** for time saved (path-dependent).
Here: caching and de-duplication removed 33% of the work (212 → 142
runner-seconds); parallelism compressed the remainder by a further 24%.

**What does parallelism cost?**
Compute and duplicated setup. Five jobs means five checkouts and five
toolchain installs, and files must move as artifacts. Parallelism buys wall
clock by spending compute — right for a QA cycle where a human is waiting,
wrong for a nightly batch job.

**Why is compiling four times a correctness problem, not just a slow one?**
Four builds are four chances to produce different bytes. Then the artifact you
tested is not provably the artifact you shipped, and every test result before
the last build is worthless.

**Caching made it faster. What else did it do?**
Made it *predictable*. The baseline's two runs differed by 24%, all of it in
network-bound steps. The optimised warm runs differ by one second. Variance
matters more than the mean when you are deciding whether to trust a red build.

## 3. GitHub Actions

**Job vs step?**
A job runs on its own runner; steps share that runner's filesystem. Jobs
parallelise, steps do not.

**How do you order jobs?**
`needs:`. This project's graph: `fast-tests` and `build-and-integration` in
parallel, then `blackbox` and `image` in parallel, then `publish` gated on
everything.

**How do secrets work, and what is the scoping rule?**
`${{ secrets.NAME }}`, injected into the environment of the step that declares
them — never into the whole job. Actions masks known secret values in logs,
but that is a safety net, not a strategy.

**How do you authenticate to AWS without storing keys?**
OIDC. Actions mints a short-lived signed token describing the repo, branch and
workflow; AWS validates it against a trust policy and returns temporary
credentials. The `sub` condition here is pinned to `refs/heads/main` —
`repo:owner/*` would let any repository you own assume the role.

**Why `if: always()` on report upload?**
Because subsequent steps are skipped on failure, and a failed run is exactly
when you need the reports.

## 4. Jenkins (the same pipeline, elsewhere)

**Map the concepts.**
job → stage; separate jobs with `needs` → `parallel {}`; `uses: actions/...` →
shared library; `secrets.*` → `withCredentials`; upload/download-artifact →
`stash`/`unstash`; `github.run_number` → `env.BUILD_NUMBER`.

**What actually differs in practice?**
The execution model. Jenkins agents are usually long-lived, so the Maven cache
is "free" — and that hides state. A stale `~/.m2` on an agent is a classic
source of "only fails on the build server". Actions runners are destroyed
after every run, so caching is explicit and reproducibility is the default.

**When would you still choose Jenkins?**
Arbitrary agents, special hardware, air-gapped networks — anywhere a hosted
runner cannot go. The trade is that someone must patch the controller and
manage plugins.

## 5. Testing strategy

**Describe your test pyramid here.**
72 unit/slice tests (~5s, no Docker), 33 Spring integration tests with real
PostgreSQL via Testcontainers (~22s), and 76 black-box Python tests against a
running platform (~3s). 181 in total.

**Why three tiers and not one?**
Each catches what the others structurally cannot. A unit test proves a
promotion transition is rejected but cannot tell you it surfaces as HTTP 409.
A Spring integration test shares the application's own object graph, so it
inherits its assumptions. A black-box test has no idea what Spring is.

**Give a concrete example of that difference.**
97 Java tests were green. The black-box suite failed five tests on its first
run — four were one bug: a catch-all `@ExceptionHandler(Exception.class)`
outranked Spring's own resolver and turned every client mistake into a 500.
Malformed JSON 400→500, unknown URL 404→500, wrong method 405→500, wrong
content type 415→500. The Java tests only ever sent requests the controllers
were written to accept.

**Why split surefire and failsafe?**
Unit tests need no Docker and finish in seconds, so they run on every push.
Integration tests need a real database and belong after packaging. Developers
get feedback in seconds and still get full verification before release — the
core mechanism for reducing QA cycle time.

**What do you do with a test for a feature that does not exist yet?**
Mark it `xfail` with a reason rather than deleting it. A missing test looks
identical to a passing one; an xfail appears in every run's summary. The auth
test carried "not implemented yet — Phase 9" for four phases and now passes.

## 6. Integration testing

**What makes a test an integration test?**
It exercises real collaborators — a real database, real HTTP — rather than
mocks. Here: Testcontainers starts a genuine PostgreSQL, so Flyway migrations
are proven to apply, not assumed.

**What is Testcontainers and why not an in-memory database?**
It runs real dependencies in Docker for the duration of a test. H2 is not
PostgreSQL: different type system, different constraint behaviour, different
SQL dialect. A schema that passes on H2 and fails on PostgreSQL is a bug you
have deferred, not avoided.

**Why is `@ServiceConnection` better than `@DynamicPropertySource`?**
It wires the container's JDBC URL, username and password into Spring
automatically. Less boilerplate and no chance of the two drifting apart.

**How do you keep an integration suite re-runnable?**
Give every run its own data. Published artifacts are immutable with no delete
endpoint, so a hard-coded tool name passes once and 409s forever. Each run
namespaces its tool and clients with a random id — which also makes concurrent
CI runs safe. This bit twice: `SecurityIT` initially wrote to the shared
`./data/artifacts` directory and failed on its second run for a reason that
had nothing to do with security.

## 7. Data-driven testing

**What is it?**
Test logic written once; cases live in data files. One parameterised function
covers fifteen inputs instead of fifteen functions. Here, 49 of 76 tests come
from four JSON/YAML files.

**Why is it better than duplicated test methods?**
Five reasons, all practical. Adding a case costs code, so people stop adding
cases. Improving an assertion means editing every copy, so nobody does.
Copy-paste drift makes coverage unknowable. The cases become invisible to
non-programmers. And gaps hide — a missing case looks exactly like the others:
absent. In a table, a missing row in a state machine is visible at a glance.

**When is it the wrong tool?**
When the test is a *sequence* rather than the same question with different
inputs. "Observe, publish a new version, observe again" cannot be a table row
without inventing a mini-language in your data file.

**How do you stop data-driven expectations from going flaky?**
Assert the invariant, not a snapshot. One case asserted a floating client
resolves to `"2.0"` and failed — another test legitimately published something
newer, and a floating client is *supposed* to move. The literal was flaky by
construction; it became a sentinel resolved at run time.

**What makes a good failure message?**
Enough to diagnose without re-running. Every assertion here prints the URL,
status, latency, body, and the `X-Request-Id` you can grep for in the service
log. If you had to reproduce it locally, the assertion helper is the defect.

## 8. Artifact repositories & Artifactory

**What is an artifact repository, and why not git?**
Git stores diffs of text and makes clones enormous when you commit binaries.
An artifact repository is built for build outputs: unique coordinates,
immutability, checksums, and lifecycle policies.

**What are artifact coordinates?**
The unique address of one build. `com.acme:data-validator:1.2` in Maven,
`registry/tools/data-validator:1.2` in Docker,
`internal-tools-local/data-validator/1.2/data-validator-1.2.jar` here.

**Which Artifactory APIs does an integration actually need?**
Three. `PUT /artifactory/{repo}/{path}` to deploy, `GET` the same to download,
`GET /artifactory/api/storage/{repo}/{path}` for metadata. Artifactory has a
very large API; a platform needs almost none of it.

**How do you test an integration with a service you cannot run?**
Against a mock HTTP server, asserting the exact request produced — method,
URL, headers, body — and how each response class is interpreted. That caught
a real bug here: passing a multi-segment path as a URI template variable
percent-encodes its slashes to `%2F`, collapsing the whole repository layout
into the root. Found offline in milliseconds; a live instance would have shown
a confusing 404 minutes later, if the container booted at all.

**Why the port/adapter split?**
Three concrete payoffs, not taste. Integration tests exercise the whole
distribution path with no Artifactory. CI does not go red because an external
service is down. Swapping to S3 or Nexus is a new adapter, not a refactor.

## 9. Artifact immutability

**What does it mean and why does it matter?**
A published coordinate never changes content. "We tested 1.2" is only
meaningful if 1.2 cannot change; otherwise a test result, an audit record, a
rollback target and a reproducible build are all guesses.

**Where is it enforced here?**
Three independent layers, each closing a different hole: `UNIQUE (tool_id,
version)` in PostgreSQL stops concurrent publishes; a write-once
`sealWith(checksum)` on the domain object stops re-uploading bytes; the stores
refuse to overwrite an existing path. ECR adds a fourth with
`--image-tag-mutability IMMUTABLE`.

**Why is the database check not redundant with the application check?**
The application check is advisory — two concurrent publishes both pass an
`exists()` test and both proceed. Only the database can arbitrate. The service
catches the resulting constraint violation and translates it to 409, so the
check-then-act race is handled rather than pretended away.

**What is the exception to immutability?**
`SNAPSHOT` versions: explicitly mutable, explicitly not for release
consumption.

## 10. Semantic versioning

**What do the parts mean?**
MAJOR breaks compatibility, MINOR adds it backward-compatibly, PATCH fixes
without changing the interface.

**Why parse the version instead of storing the string?**
String ordering is wrong: lexicographically `"1.10" < "1.9"`, but 1.10 is
newer. Sorting, "latest" and range queries all need integers. The raw string
is kept too, so `1.0` never silently becomes `1.0.0` — the version string is
part of the artifact's identity.

**Why does the platform reject "latest" as a lookup key?**
Because a silent fallback turns a typo — `1.20` for `1.2` — into a successful
download of the wrong bytes, the worst failure a distribution system can have.
`latest` is legal only in a client's *configuration*, where it is a recorded
decision by a named consumer.

## 11. Artifact versioning & promotion

**What is promotion?**
Moving the *same bytes* through a lifecycle — draft, published, deprecated,
revoked — by changing a label, never by rebuilding.

**Why not rebuild for each environment?**
A rebuild produces different bytes: different timestamps, a differently
resolved transitive dependency, a different compiler patch. Then "the thing we
tested in staging" and "the thing we released" are not the same artifact, and
every test result before the rebuild is worthless. Promotion is what makes
*build once, deploy everywhere* true rather than aspirational. There is a test
asserting the checksum is identical before and after.

**Why is REVOKED terminal?**
Un-revoking would let a consumer who correctly stopped using an artifact be
silently handed it again.

**Why does DRAFT → PUBLISHED require an artifact?**
So "PUBLISHED" can never mean "a row exists but there is nothing to download".
It is the release gate, and the CI pipeline exercises it on every run by
publishing its own build through it.

## 12. Version pinning & rollback

**Explain rollback here, and why immutability is what makes it work.**
`ToolVersion` rows are immutable; `ClientToolConfiguration` rows are mutable.
Rollback is a single `PUT` updating a pointer. It is *safe* only because 1.2
is still byte-identical to the 1.2 that was tested. With mutable artifacts,
"roll back to 1.2" means "rebuild something and call it 1.2" — not a rollback.

**`LATEST` reintroduces floating versions. Why is it acceptable?**
It is opt-in per client, so the blast radius is only those who chose it; the
choice is recorded and auditable; and resolution still produces a concrete,
logged version. The danger of `latest` was never floating — it was floating
*invisibly*.

**What did writing the test cases reveal about `latest`?**
That `findLatestVersion` returned the newest version of *any* status — so
revoking the newest release would have broken every floating consumer instead
of protecting them. Now filtered to PUBLISHED only.

**Why 410 for a revoked version rather than 404?**
404 says "never existed, check your spelling"; 410 says "existed and was
withdrawn, you must move". Different diagnosis, different fix.

## 13. Java

**Why a record for `SemanticVersion` and a class for `ToolVersion`?**
A record is an immutable value object with equality by content — exactly a
version number. `ToolVersion` is a JPA entity with identity, a lifecycle and a
mutable status field; records cannot be entities.

**How does the write-once checksum work?**
`sealWith(sha256)` throws `IllegalStateException` if a checksum is already
set. Immutability enforced in the domain object rather than trusted to
callers.

**Why `MessageDigest.isEqual` instead of `String.equals` for the API key?**
`equals` short-circuits on the first differing byte, so its timing leaks how
many leading characters were correct — a timing oracle that recovers the key
one character at a time. `isEqual` compares every byte regardless.

**What does `Comparator.comparingInt(...).thenComparingInt(...)` give you?**
A composed comparator for major/minor/patch, so `1.10` correctly sorts above
`1.9`. The unit test asserts both the wrong behaviour (`"1.10".compareTo("1.9")`
is negative) and the right one, so the reason the code exists is visible.

## 14. Spring Boot

**`@Controller` vs `@RestController`?**
`@RestController` = `@Controller` + `@ResponseBody`: return values are
serialised into the body rather than resolved as view names.

**Why is `@Transactional` on the service, not the controller?**
A transaction should span one unit of business work. On the controller it
would hold a database transaction open across serialisation and validation,
and leak persistence into the web layer.

**What is `open-in-view` and why disable it?**
Enabled (the Boot default) it holds the persistence session open until the
response is rendered, so lazy proxies load silently during serialisation —
hidden N+1 queries. Disabled, lazy access fails loudly in development. That is
why `ToolVersionResponse` takes the tool name as a parameter instead of
calling `getTool().getName()`.

**Why `ddl-auto: validate` with Flyway?**
Flyway owns the schema; Hibernate only checks it and refuses to start on a
mismatch. It caught a real bug immediately: `CHAR(64)` is reported by
PostgreSQL as `bpchar` and does not match the `varchar(64)` Hibernate expects.

**Why extend `ResponseEntityExceptionHandler`?**
Because a bare `@ExceptionHandler(Exception.class)` is more specific than
nothing and outranks Spring's own `DefaultHandlerExceptionResolver`, swallowing
framework exceptions and turning every client mistake into a 500. Extending it
restores their precedence while `handleExceptionInternal` keeps everything in
problem+json.

**Why did `@WebMvcTest` start returning 401 after adding Spring Security?**
The slice pulls in Security's *default* chain — deny-all with CSRF — not the
application's. `@Import(SecurityConfig.class)` makes the slice test the
configuration actually shipped.

**Why did `/actuator/prometheus` 404 in tests but work at runtime?**
Spring Boot disables metrics export in tests by default
(`DisableObservabilityContextCustomizer`), so no `PrometheusMeterRegistry`
bean exists and the endpoint is never registered. `@AutoConfigureObservability`
opts back in. The default is sensible — it stops every test run pushing
metrics to a real backend.

## 15. REST API design

**How did you version the API, and what is the distinction worth drawing?**
`/api/v1/...` in the path. Two different versions live in this system: the
version of the HTTP *contract* and the version of the distributed *artifact*.
They evolve independently.

**Why `PUT` and not `POST` for setting a client's version?**
A client has exactly one version decision per tool at a known URL. `PUT` means
"make the state here exactly this" and is idempotent, so a retried deployment
cannot create duplicates. `POST` implies appending a subordinate resource.

**Why an envelope on collection endpoints?**
A bare JSON array is a dead end: the day you need paging or totals you break
every client. `{data, pagination}` leaves room to grow.

**What is RFC 7807 and why use it?**
A standard error media type, `application/problem+json`, with defined fields
`type`, `title`, `status`, `detail`, `instance`. Clients assert on a stable
`type` URI instead of parsing English prose that gets reworded every sprint.

**Justify your status codes.**
200/201 success; 400 malformed version; **404** unknown coordinates; **409**
immutability violations and illegal promotions; **410** revoked (existed, was
withdrawn); **415** wrong content type; **422** body failed validation;
**502** registry/store drift and checksum mismatch — our inconsistency, not
the caller's mistake, which points the on-call engineer at the right system.

**Why is a 404 body checked for what it does *not* contain?**
`assert_no_artifact_leaked` asserts no `artifactPath` or `checksumSha256`
appears in an error body. If a failed lookup returned usable coordinates,
someone would eventually use them. That assertion is what enforces "no silent
fallback".

## 16. PostgreSQL

**What is Flyway doing and what is the immutability rule?**
Applying versioned SQL files exactly once each, recorded in
`flyway_schema_history`. An applied migration is immutable — Flyway checksums
it and refuses to run if it changed. To alter the schema you add `V3`; you
never edit `V1`.

**Which constraints does this schema rely on, and why in the database?**
`UNIQUE (tool_id, version)` for artifact immutability; a foreign key on
`pinned_version_id` so you cannot pin to a nonexistent version; `ON DELETE
RESTRICT` so you cannot delete a version someone depends on; `CHECK`
constraints tying `selector` to the presence of a pinned version. Application
checks are bypassed by every other write path — another service, a migration,
a manual `UPDATE`. Constraints are not.

**How do you avoid N+1 on the configurations endpoint?**
`join fetch` in the query. Without it each configuration lazily loads its tool
in its own query — and with `open-in-view` disabled it does not even work,
because the session is already closed.

**Why store `major/minor/patch` as separate integer columns?**
So the database can sort numerically. An index on
`(tool_id, major DESC, minor DESC, patch DESC)` makes "versions of X, newest
first" cheap.

**Why `TIMESTAMPTZ` rather than `TIMESTAMP`?**
`TIMESTAMP` has no time zone, so the same value means different instants
depending on who reads it. For an audit trail that is a defect.

## 17. Docker

**What does multi-stage buy you?**
Only the final stage ships. The JDK, source tree and populated `~/.m2` stay in
the build stages: **673 MB → 376 MB**.

**Why does layer order matter?**
Docker reuses a layer only if it and everything before it are unchanged.
Dependencies change monthly, source hourly — so dependencies are resolved
before the source is copied in. Reverse them and a one-character edit throws
away the dependency download. That single choice is the difference between a
19-second and a 115-second image build here.

**Why is `localhost` wrong between containers?**
Inside a container it is that container's own loopback. Use the service name;
Docker's DNS resolves it to the container's current IP, which changes on every
restart. And use the *container's* port — the published host mapping is
irrelevant on the internal network.

**Why is `depends_on` not enough?**
It waits for the container to *start*, not for the service to be *ready*. A
starting PostgreSQL still refuses connections and Flyway races it.
`condition: service_healthy` waits for the healthcheck.

**Why exec form for `ENTRYPOINT`?**
Shell form makes the shell PID 1; it swallows SIGTERM and the container is
eventually SIGKILLed instead of shutting down gracefully. Exec form makes the
JVM PID 1.

**Why create a directory in the image that a volume will be mounted over?**
Docker seeds an empty named volume with that path's contents *and ownership*.
Create and `chown` it before `USER app` and the volume is app-owned; skip it
and the volume arrives root-owned and a correctly non-root container cannot
write to it.

**Why `MaxRAMPercentage`?**
Without a container-aware heap setting the JVM sizes the heap from the host's
memory and gets OOM-killed inside a 512 MiB task.

## 18. AWS & deployment automation

**Why ECS Fargate over EC2 for this?**
Two answers. To see it running cheaply today, EC2 free tier running the same
compose stack. To learn what job descriptions ask for, Fargate — and it fits
this project because **a task definition revision is an immutable versioned
artifact**: rolling back is pointing the service at revision 41, which still
references an immutable ECR digest. Same discipline as tool versions, one
layer up.

**Execution role vs task role?**
The execution role belongs to the ECS *agent* — pull the image, write logs,
read secrets — and is used before your code runs. The task role belongs to
your *application*. This app calls no AWS APIs, so its task role is empty:
least privilege means an empty policy when nothing is needed.

**How do secrets reach the container?**
The task definition holds only the *ARN* of an SSM `SecureString`; the ECS
agent fetches the value at start-up. It is never in git, never in the task
definition, never in `describe-task-definition` output.

**Why never `:latest` in a task definition?**
A tag that moves cannot be rolled back to. You cannot say which bytes are
running, and a "rollback" restores a tag whose meaning has already changed.

**What is the most expensive thing people leave running?**
A NAT gateway, ~$32/month, billed hourly whether traffic flows or not and not
removed when you delete the service in front of it. Then load balancers at
~$18. A learning deployment is one task in a public subnet: ~$9/month.

**Why does the deploy job wait on `ecs wait services-stable`?**
Otherwise the job goes green while the new version crash-loops, and "deployed
successfully" means nothing.

## 19. Release engineering (the discipline)

**What is release engineering actually about?**
Making the path from commit to production **reproducible, reversible and
auditable**. Every mechanism in this project serves one of those three:
immutable artifacts and pinned dependencies for reproducibility; pinning and
promotion for reversibility; checksums, correlation ids and change logging for
auditability.

**What is the single most important property of a release system?**
That you can answer "which exact bytes are running, and can I put the previous
ones back?" Everything else is optimisation.

**How does this project make a build reproducible?**
Dependencies pinned exactly (Maven BOM, `==` in Python, exact npm versions);
the artifact built once and passed downstream rather than rebuilt; a SHA-256
recorded at publish and verified on every download; immutable coordinates in
the registry and in ECR; and CI-derived version numbers that cannot collide.

**What does "reduce QA cycle time" mean concretely?**
Shorten the loop between a change and a trustworthy verdict on it. Here that
is three things: tiering tests so the fast signal arrives in seconds; removing
waste from the pipeline (measured, 212s → 108s); and making failures
diagnosable without re-running — correlation ids, problem+json, reports
uploaded even on failure.

**What would you do next on this project?**
Per-caller API keys with scopes; an S3 artifact adapter so storage is durable
on Fargate; and a durable audit log — every state change is already logged
with old and new values, but to stdout, and an audit trail you can delete is
not one.
