# CI/CD pipeline

## 1. What the pipeline is for

A pipeline exists to answer one question on every commit: **is this safe to
release?** Everything else — caching, parallelism, artifact publishing — is in
service of answering it *correctly* and *quickly*, in that order.

"Correctly" first. A fast pipeline that misses bugs is worse than no pipeline,
because people trust it.

## 2. The stages, and why each exists

| # | Stage | Why it exists | Fails the build when |
|---|-------|---------------|----------------------|
| 1 | **Checkout** | Every run starts from an empty runner; nothing persists between runs by default | the ref cannot be fetched |
| 2 | **Set up Java** | Pin the JDK. "Works on my machine" usually means "on my JDK" | the version is unavailable |
| 3 | **Unit + slice tests** | Fastest, most precise feedback. No Docker, no database | any test fails |
| 4 | **Integration tests** | Real PostgreSQL via Testcontainers — proves the pieces fit | any test fails, or a container cannot start |
| 5 | **Package** | Produce the deployable jar | compilation or repackaging fails |
| 6 | **Derive version** | CI owns version numbers, not humans | — |
| 7 | **Start dependencies** | The black-box suite needs a real running platform | the database does not come up |
| 8 | **Start the application** | Run the artifact that was just built, not a rebuild | it fails to become healthy |
| 9 | **Black-box tests** | Data-driven pytest suite over real HTTP | any test fails |
| 10 | **Publish the artifact** | Register + upload + promote in the registry | the release gate rejects it |
| 11 | **Build the image** | The deployable unit for ECS/Kubernetes | the build fails |
| 12 | **Upload reports** | `if: always()` — you need reports most when the run failed | — |

Any non-zero exit code fails the job and stops everything after it. That is
the default, and it is the correct default: **a pipeline that continues past a
failure is just an expensive log file.**

### Why CI derives the version

```yaml
- run: echo "value=0.1.${{ github.run_number }}" >> "$GITHUB_OUTPUT"
```

`github.run_number` is monotonic and unique, so every green build produces
coordinates that can never collide with a previous one. Humans forget to bump
versions; CI cannot. This is what makes the immutability guarantee in the
registry enforceable rather than aspirational.

### The pipeline dogfoods the platform

After the tests pass, the pipeline registers *its own build* in the registry it
just built, uploads the jar, and promotes `DRAFT → PUBLISHED`:

```
register version: 201     (DRAFT - metadata only)
upload artifact : 201     (55 MB jar, sealed with its SHA-256)
promote         : 200     (the release gate accepts it)
```

If the release gate ever regressed — if a version could be published with no
artifact behind it — the pipeline itself would be the thing that noticed.

## 3. Secret management

No credential appears in any file in this repository.

```yaml
- name: Publish to Artifactory
  env:
    ARTIFACTORY_URL:      ${{ secrets.ARTIFACTORY_URL }}
    ARTIFACTORY_USERNAME: ${{ secrets.ARTIFACTORY_USERNAME }}
    ARTIFACTORY_PASSWORD: ${{ secrets.ARTIFACTORY_PASSWORD }}
  run: |
    if [ -z "${ARTIFACTORY_URL:-}" ]; then
      echo "::notice::not configured - skipping"; exit 0
    fi
    curl -sSf -u "$ARTIFACTORY_USERNAME:$ARTIFACTORY_PASSWORD" -T "$JAR" "$ARTIFACTORY_URL/..."
```

Four properties worth naming:

1. **Scoped to one step.** The secret is in that step's environment and no
   other. A malicious or careless step elsewhere in the job cannot read it.
2. **Never written to disk**, never echoed, never passed as a command-line
   argument (argv is visible to other processes).
3. **Masked in logs.** Actions redacts registered secret values automatically —
   but that is a safety net, not a strategy.
4. **Degrades gracefully.** Unconfigured means "skip with a notice", not
   "fail" and not "publish anonymously".

The same application code runs in all three environments; only the injection
mechanism differs — `.env` locally, Actions Secrets in CI, SSM/Secrets Manager
on AWS. That is the entire point of externalised configuration.

## 4. The baseline: measured, not guessed

`.github/workflows/ci-baseline.yml` is **deliberately unoptimised**. It is the
"before" number, and it is not a strawman — every problem in it is one people
ship, because pipelines grow one step at a time and nobody revisits them whole.

### Measured result

Two runs, `ubuntu-latest`, single job. **Two samples, because one is not a
measurement** — and the spread between them turns out to matter (see below).

| Run | Wall clock |
|-----|-----------:|
| [33100207771](https://github.com/Adityag025/internal-tool-platform/actions/runs/33100207771) | **201 s** |
| [33100602952](https://github.com/Adityag025/internal-tool-platform/actions/runs/33100602952) | **250 s** |
| **Mean** | **~225 s** |

Per-step breakdown of the first run:

```
   2s  Set up job
   1s  Checkout
   0s  Set up Java 21            (JDK 21 is preinstalled on the runner image)
  23s  Unit and slice tests      <- includes the full Maven dependency download
  40s  Integration tests         <- clean + recompile + rerun unit tests + Testcontainers
   5s  Package                   <- clean + a third compile
   0s  Derive version
   1s  Start PostgreSQL
  45s  Wait for PostgreSQL       <- a fixed sleep. Postgres was ready in ~3s.
  40s  Start the Tool Registry   <- another fixed sleep. It was up in ~8s.
   0s  Set up Python
   5s  Install Python deps       <- no cache
   3s  Data-driven tests         <- 75 tests
   0s  Register and publish
  27s  Build Docker image        <- recompiles the whole app inside the image
   3s  Upload jar
   1s  Upload reports
  ----
 201s  TOTAL
```

### The variance is the first finding

The same commit, the same runner class, 49 seconds apart — a 24% spread. All
of it in the network-bound steps:

| Step | Run 1 | Run 2 | Δ |
|------|------:|------:|--:|
| Unit and slice tests (includes dependency download) | 23 s | 40 s | +17 s |
| Install Python deps | 5 s | 13 s | +8 s |
| Build Docker image (downloads everything again) | 27 s | 45 s | +18 s |
| Fixed sleeps | 85 s | 85 s | 0 |

Every second of that variance is in work that **caching removes entirely**.
The sleeps, by contrast, are perfectly consistent — consistently wasted.

This is why a single "before" number would be dishonest, and why the
improvement in Phase 6 is quoted against the **mean of repeated runs**, not
against the slowest baseline anyone ever saw.

### Where the time actually goes

| Category | Seconds | Share |
|----------|--------:|------:|
| **Fixed sleeps** | 85 | **38%** |
| Redundant compiles + uncached downloads | ~75 | 33% |
| Genuinely useful work (tests, packaging, publishing) | ~55 | 25% |
| Fixed overhead (checkout, setup, uploads) | ~10 | 4% |

*(shares computed against the ~225 s mean)*

**Nearly half the pipeline is spent sleeping.** That is the most common single
finding when anyone measures a pipeline for the first time, and it is why the
rule is *measure before optimising*: the intuitive answer here would have been
"caching", and caching is not the biggest win.

### The seven deliberate problems

1. **No Maven dependency cache** — `setup-java` without `cache: maven`.
2. **`clean` three separate times** — three full compiles; unit tests run twice.
3. **Fixed sleeps** — waits a constant instead of the actual event.
4. **No pip cache.**
5. **Docker rebuilds the app** — a fourth compile, inside the image, with no
   layer separation, so nothing can ever be cached.
6. **One single job** — nothing can run in parallel.
7. **No fail-fast tiering** — cheap checks are not front-loaded.

### An honest caveat about the absolute numbers

GitHub runners have a very fast link to Maven Central: the full dependency
download costs ~10 s there and several minutes on a home connection. So the
*absolute* numbers are runner-dependent, and quoting them as universal would
be dishonest.

What is comparable is the **before/after on the same runner class, on the same
commit** — which is exactly how Phase 6 measures. Both pipelines trigger on
every push to `main`, so they race on identical input.

## 5. Two bugs the pipeline found immediately

Both were invisible locally. This is what a pipeline is *for*.

**1. `docker compose` interpolates the whole file before applying profiles.**

```yaml
JF_SHARED_SECURITY_MASTERKEY: ${ARTIFACTORY_MASTER_KEY:?set it in .env}
```

The `${VAR:?error}` form fails the *entire* command when the variable is
unset — including `docker compose up postgres`, which does not touch the
Artifactory service at all. It worked locally only because `.env` existed.

**2. An unanchored `.gitignore` pattern hid the entire test suite's data.**

```gitignore
data/     # matches a directory called "data" at ANY depth
```

That was meant for the local artifact store. It also matched
`integration-tests/data/` — so all four case files were never committed. The
suite was 75/75 green locally against files CI could not see, and the first
run failed with `FileNotFoundError` on every one of them.

An unanchored gitignore pattern is a works-on-my-machine generator. Anchored:

```gitignore
/backend/data/
/data/
```

## 6. The same pipeline in Jenkins

The stages are identical; only the syntax and the operational model differ.

```groovy
pipeline {
    agent { label 'linux && docker' }

    environment {
        VERSION = "0.1.${env.BUILD_NUMBER}"          // = github.run_number
    }

    stages {
        stage('Build & unit tests') {
            steps { sh './mvnw -B test' }
            post { always { junit 'backend/target/surefire-reports/*.xml' } }
        }
        stage('Integration tests') {
            steps { sh './mvnw -B verify' }
            post { always { junit 'backend/target/failsafe-reports/*.xml' } }
        }
        stage('Publish to Artifactory') {
            steps {
                withCredentials([usernamePassword(
                        credentialsId: 'artifactory-publisher',   // = Actions Secrets
                        usernameVariable: 'ART_USER',
                        passwordVariable: 'ART_PASS')]) {
                    sh 'curl -sSf -u "$ART_USER:$ART_PASS" -T target/tool-registry.jar "$ART_URL/..."'
                }
            }
        }
    }
    post { always { archiveArtifacts artifacts: 'backend/target/*.jar' } }
}
```

| Concept | GitHub Actions | Jenkins |
|---------|----------------|---------|
| Unit of work | `job` | `stage` |
| Parallelism | separate `jobs` with `needs:` | `parallel { }` blocks |
| Reusable step | `uses: actions/...` | shared library / plugin |
| Secrets | `secrets.*`, scoped per step | `withCredentials`, scoped per block |
| Passing files between units | `upload-artifact` / `download-artifact` | `stash` / `unstash` |
| Caching | `actions/cache` | workspace reuse, or a cache plugin |
| Build number | `github.run_number` | `env.BUILD_NUMBER` |
| Runner | ephemeral VM, clean every run | usually a long-lived agent |

The differences that actually bite:

- **Jenkins agents are typically persistent.** The Maven cache is "free"
  because the workspace survives — which is why Jenkins pipelines often *look*
  faster while hiding state that makes builds non-reproducible. A stale
  `~/.m2` on an agent is a classic source of "only fails on build server".
  Actions runners are destroyed after every run, so caching is explicit and
  reproducibility is the default.
- **Jenkins needs infrastructure.** Someone patches the controller, manages
  plugins, and secures it. Actions is managed.
- **Jenkins is more flexible** — arbitrary agents, hardware, air-gapped
  networks — which matters if you must build on something a hosted runner
  cannot provide.

## 7. Running it

```bash
gh workflow run "CI (baseline)" --ref main    # trigger on demand
gh run list --workflow="CI (baseline)"        # see recent runs
gh run view <id>                              # per-step results
gh run view <id> --log-failed                 # only the failing step's log
```

Both pipelines also run automatically on every push to `main`.
