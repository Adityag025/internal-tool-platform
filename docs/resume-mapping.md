# Resume mapping

How each responsibility on your resume maps to something demonstrable in this
project — and, just as importantly, where that mapping stops.

---

## The rule

Three things, never collapsed into one:

| | What it is | Who can state it |
|---|---|---|
| **(A) Resume claim** | what you actually did in your job | **only you** — this document leaves it blank |
| **(B) Demonstrated here** | code in this repository you can open, run, and be questioned on | verifiable by anyone |
| **(C) Architectural example** | designs written down but not built or not run | must be labelled as such |

**Building something in a personal project never converts into having done it
at work.** This project does not add a line to your employment history. What
it adds is the ability to *answer follow-up questions* about work you really
did — which is what actually separates a credible candidate from one who
memorised the vocabulary.

The safe construction, every time:

> "At work I was responsible for **[A]**. To understand the mechanics
> properly I also built a personal project that implements **[B]** — here is
> the repository."

Two separate sentences. The second never borrows the first's authority.

---

## Responsibility 1 — "Optimising the CI/CD pipeline to reduce QA cycle time"

**(A) Your resume claim** — only you know the scope: which pipeline, whose,
how much of the work was yours, what the measured effect was. Write it down
before an interview, and be ready to say what you personally changed.

**(B) What this project demonstrates**

| Concept | Evidence |
|---|---|
| Building a pipeline at all | `.github/workflows/ci-baseline.yml`, 16 stages |
| **Measuring before optimising** | four baseline runs: 201s, 250s, 197s, 199s |
| Finding the real bottleneck | 85s (38%) was fixed `sleep`, not the caching everyone assumes |
| Optimising | `ci-optimised.yml` — readiness polling, Maven/pip caching, one build, layer cache, 5 parallel jobs |
| **Quantifying honestly** | 212s → 108s mean, `((212−108)/212)×100 = 49.1%` |
| Attributing the gain | caching removed 33% of the *work*; parallelism compressed the rest by 24% |
| Naming the limit | cold cache: 208s — **no improvement at all** |
| Test tiering as the QA lever | surefire/failsafe split, pytest markers |
| Reducing variance | baseline spread 24%; optimised warm runs differ by 1s |

**(C) Architectural example only** — Jenkins. `docs/ci-cd.md` §6 maps every
stage to Jenkins syntax and explains the differences that bite, but no Jenkins
pipeline was run here.

**How to talk about it**

> "At work I was responsible for the CI/CD pipeline and reducing QA cycle
> time. To understand pipeline optimisation properly I also built a personal
> project where I wrote the pipeline twice — one deliberately unoptimised as a
> baseline — and measured both. Steady state went from 212 to 108 seconds,
> 49%. The part I found most interesting is that with caches purged the
> optimised one is 208 seconds, so the entire gain is a caching effect. And
> the biggest single win wasn't caching at all — it was 85 seconds of fixed
> `sleep` statements, 38% of the run, waiting for a database that was ready
> in three seconds."

That last sentence is the one that lands. It shows you measured rather than
guessed, and that the measurement surprised you.

---

## Responsibility 2 — "Automating integration tests by creating a data-driven integration testing framework"

**(A) Your resume claim** — yours to state: what the framework tested, how
many teams used it, what it replaced.

**(B) What this project demonstrates**

| Concept | Evidence |
|---|---|
| A reusable framework, not a pile of tests | `integration-tests/framework/` — loader, typed client, assertion library |
| Data-driven | **49 of 76 tests** from four JSON/YAML case files |
| Parameterisation with readable ids | `parametrized()`, explicit `id` per case |
| Reusable fixtures | session-scoped seeding, readiness gate, name resolvers |
| **Test isolation** | run-scoped namespacing; re-runnable against immutable data; concurrent-safe |
| Negative and edge cases | path traversal, corrupted checksums, malformed JSON, duplicates, revoked, wrong method/content-type |
| Readable results | JUnit XML + self-contained HTML, markers for tiering |
| Diagnosable failures | every assertion prints URL, status, latency, body, request id |
| **It found real bugs** | four 500s that should have been 400/404/405/415, plus a `latest` design flaw |
| Handling known gaps | the auth test lived as an `xfail` for four phases, then went green |

**(C) Architectural example only** — nothing. This one is fully built and
runnable: `./scripts/run-integration-tests.sh`.

**How to talk about it**

> "At work I built a data-driven integration testing framework. In my personal
> project I built the same shape again: test logic written once, cases in JSON
> and YAML that anyone can read and extend — about fifty of seventy-six tests
> come from four data files. It's black-box, over HTTP, and on its first run
> it found four bugs that ninety-seven existing Java tests had missed. A
> catch-all exception handler was outranking Spring's own resolver, so every
> client mistake — malformed JSON, unknown URL, wrong method — was being
> reported as a 500. The in-process tests structurally couldn't catch it,
> because they only ever sent requests the controllers were written to accept."

**This is your strongest single anecdote.** It demonstrates the concept, a
real bug, *why* the bug was invisible to the other tests, and the fix.

---

## Responsibility 3 — "Designed and implemented an internal Artifactory repository enabling dynamic tool versioning and distribution"

**(A) Your resume claim** — yours: which tools, how many consumers, what
existed before.

**(B) What this project demonstrates**

| Concept | Evidence |
|---|---|
| Registry design | `tools`, `tool_versions`, `clients`, `client_tool_configuration` |
| Artifact coordinates | `data-validator/1.2/data-validator-1.2.jar` |
| **Immutability, enforced** | DB unique constraint + write-once seal + store refusal + ECR immutable tags |
| Semantic versioning | parsed into integers, because `"1.10" < "1.9"` as strings |
| **Dynamic version loading** | client → configuration → exact version → coordinates → verified bytes |
| Exact resolution, never fallback | 404 for a missing version; an error body carries no coordinates |
| Version pinning | three clients on three versions of one tool, simultaneously |
| Rollback | one idempotent `PUT`; the artifact is never touched |
| Opt-in `latest` | recorded per client, still resolved to a concrete logged version |
| Checksums | SHA-256 at upload, verified on download, returned for the client to re-verify |
| Promotion | `DRAFT → PUBLISHED ↔ DEPRECATED → REVOKED`, same bytes throughout |
| Artifactory integration | `ArtifactoryArtifactStore` over the real REST API, verified against a mock server |
| Distribution to clients | TypeScript CLI that names no version and verifies the SHA-256 locally |

**(C) Architectural example / not run**

- **A live Artifactory instance.** The container was configured and attempted
  three times; it failed on master key, then join key, then Access-service
  timeout under memory pressure (`docs/artifactory.md` §10). The **adapter** is
  verified against a mock HTTP server — which caught a real bug a live
  instance would only have shown as a confusing 404.
- **AWS.** Everything under `deploy/aws/` is written and validated, and
  **nothing has been provisioned**.

**How to talk about it**

> "At work I designed an internal Artifactory repository for tool versioning
> and distribution. In my personal project I built the registry side of that
> myself: a service that stores tool versions with their coordinates and
> SHA-256, lets each client pin an exact version, and serves them the bytes.
> The design decision I'd point at is the asymmetry — version rows are
> immutable, client configuration rows are mutable — because that's what makes
> rollback a single pointer update to a build that's still byte-identical to
> what was tested. I integrated against the Artifactory REST API and verified
> the adapter against a mock server; I didn't get a production Artifactory
> instance running."

---

## Phrases to never use

| Do not say | Because |
|---|---|
| "I deployed this to AWS" | Nothing was provisioned. Say "I wrote the ECS deployment and can walk you through it." |
| "I ran Artifactory in production" | The container never booted here. Say "I integrated against its REST API." |
| "I reduced our pipeline by 49%" | That number is from *this project*. Say "in a project of mine, I measured 212 to 108 seconds." |
| "We used this architecture at [employer]" | You do not know that this matches what they used. |
| Blurring "at work" and "in my project" into one sentence | The listener will hear the stronger claim, and the follow-up will expose it. |

**The test to apply:** if the interviewer asked "walk me through the incident
where that broke in production", could you answer? If not, do not phrase it as
production experience.

---

## Listing the project itself

On a resume, under **Projects**, not under employment:

> **Internal Developer Tool Distribution Platform** — *personal project* ·
> [github.com/Adityag025/internal-tool-platform](https://github.com/Adityag025/internal-tool-platform)
> Java 21 / Spring Boot / PostgreSQL / Python / TypeScript / Docker / GitHub Actions
> - Registry serving immutable versioned artifacts with per-client version pinning, SHA-256 verification and a promotion lifecycle
> - Data-driven pytest framework (49 of 76 tests from JSON/YAML case files) that found four defects 97 in-process tests missed
> - Measured CI optimisation: 212s → 108s steady state, with the cold-cache limit documented

Three bullets, each with a number, each verifiable by opening the repository.
The word *personal project* does the honesty work.

---

## What this project genuinely bought you

Not a line on your employment history. Something more useful in an interview:

1. **You can answer the second question.** Most candidates can define
   immutable artifacts. Far fewer can say *where* immutability is enforced and
   why the database check is not redundant with the application check.
2. **You have real numbers.** 212 → 108, 673 MB → 376 MB, 49 of 76 tests,
   four bugs found. Specific numbers with a story behind them are hard to
   fake and easy to remember.
3. **You have real failures.** The `bpchar` mismatch, the `%2F` path
   collapse, the gitignore that hid the test data, the filter that skipped
   GET, the Artifactory container that would not boot. Being able to describe
   a bug you found and fixed is worth more than any feature you built.
4. **You know where your knowledge stops.** Saying "I wrote the ECS
   deployment but didn't provision it" is a stronger signal than a vague claim
   — it tells the interviewer your other statements can be trusted at face
   value.
