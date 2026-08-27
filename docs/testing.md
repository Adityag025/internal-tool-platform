# Testing strategy

## 1. Three tiers, three purposes

| Tier | Where | Count | Time | Needs | Answers |
|------|-------|-------|------|-------|---------|
| **Unit / slice** | `backend/src/test` (surefire) | 72 | ~5 s | nothing | "is the logic right?" |
| **Component integration** | `backend/src/test/.../it` (failsafe) | 25 | ~19 s | Docker | "do the pieces fit?" |
| **Black-box API** | `integration-tests/` (pytest) | 75 | ~2 s | a running platform | "does it behave correctly from outside?" |

They are not redundant. Each catches a class of bug the others structurally
cannot:

- A **unit test** knows the internals, so it can prove a promotion transition
  is rejected — but it cannot tell you the rejection surfaces as HTTP 409.
- A **Spring integration test** shares the application's own object graph, so
  it inherits the app's assumptions. It exercised the error handler through
  `MockMvc` and through `TestRestTemplate`, and still never noticed that a
  typo'd URL returned 500.
- A **black-box test** has no idea what Spring is. It just sends requests a
  real client would send — including ones no controller was written for.

That last gap is not hypothetical. See §5.

## 2. What "data-driven" means here

The **shape** of a test is code. The **cases** are data.

```
integration-tests/
├── data/                          <- the cases (JSON / YAML)
│   ├── tool_version_tests.json        15 version-lookup cases
│   ├── client_resolution_tests.yaml    9 client-resolution cases
│   ├── promotion_tests.yaml            9 state-machine transitions
│   └── negative_cases.yaml            16 malformed-input cases
├── framework/                     <- reusable machinery
│   ├── loader.py                      read JSON/YAML, build readable ids
│   ├── client.py                      typed API wrapper, never asserts
│   └── assertions.py                  validators with diagnostic messages
├── tests/                         <- one assertion path per behaviour
└── conftest.py                    <- fixtures: seeding, isolation, readiness
```

One test function covers fifteen cases:

```python
CASES, IDS = parametrized("tool_version_tests.json")

@pytest.mark.parametrize("case", CASES, ids=IDS)
def test_version_lookup(api, resolve_tool, case):
    response = api.get_version(resolve_tool(case["tool"]), case["version"])
    if case["expected_status"] == 200:
        assert_version_payload(response, tool, case["version"])
    else:
        assert_problem(response, case["expected_status"], case.get("expected_type"))
        assert_no_artifact_leaked(response)
```

Adding a sixteenth case is four lines of JSON.

## 3. Why not just write one test method per case?

The duplicated version of that file is ~15 near-identical functions differing
in two string literals. Five things go wrong — all of them in practice, not in
theory:

1. **Adding a case costs code**, so people stop adding cases. The suite stops
   growing exactly when the system gets complicated enough to need it.
2. **Improving an assertion means editing every copy.** So nobody improves it,
   and the copies people miss silently keep testing less than the others.
3. **Copy-paste drift.** One function checks the status; its neighbour also
   checks the body. Six months later nobody can say what is actually covered.
4. **The cases become invisible to non-programmers.** A JSON table can be read
   and extended by a QA engineer, a product owner, or a support engineer who
   just found a new edge case in production.
5. **Gaps hide.** A missing case looks exactly like the other fifteen: absent.
   In a table, a missing row in a state machine is visible at a glance.

The inversion is the point: **one well-reviewed assertion path, N cases in a
file anyone can read.** The data file becomes an executable specification.

`data/promotion_tests.yaml` is the clearest example — a state machine's
transition table is *naturally* data, and writing it as nine functions makes
the one missing transition impossible to spot.

### When NOT to use it

Data-driven is for *the same question asked with different inputs*. It is the
wrong tool for a **sequence**: observe, mutate, observe again. Those stay as
ordinary test functions — see
`test_publishing_a_release_does_not_move_pinned_clients`, which has to publish
a version in the middle to mean anything.

## 4. Fixtures, and why the suite is re-runnable

Published artifacts are immutable and there is no delete endpoint — by design.
So a suite that hard-coded `data-validator` would pass once and then fail with
409s forever.

Every run therefore **namespaces its own data**:

```python
tool = f"data-validator-{run_id}"        # run_id = uuid4().hex[:8]
```

Data files still say `data-validator` for readability; a `resolve_tool`
fixture maps the logical name onto the real one, and passes anything else
through untouched so `ghost-tool` stays genuinely absent.

Consequences worth naming:

- The suite can run twice in a row. It does, in this repo, and both runs are green.
- Two runs can execute **concurrently** against the same server without
  colliding — which matters the moment CI runs on several branches at once.
- Mutating tests take a `fresh_version` fixture rather than reusing seeded
  coordinates.

Other fixtures earning their place:

| Fixture | Scope | Why |
|---------|-------|-----|
| `platform_ready` | session, autouse | Turns "40 confusing ConnectionErrors" into one sentence telling you what to start |
| `seeded` | session | ~30 HTTP calls, paid once instead of once per test |
| `resolve_tool` / `resolve_client` | session | Keeps data files readable while runs stay isolated |
| `fresh_version` | function | Unused coordinates for tests that publish |

### The flakiness this design still caught

The `client-latest` case originally asserted `expected_version: "2.0"`. It
failed — because an earlier test in the suite legitimately published a newer
version, and a floating client is *supposed* to move when that happens.

The literal was flaky by construction. The fix was a sentinel resolved at run
time:

```yaml
expected_version: "<newest-published>"
```

The lesson generalises: **a data-driven expectation must be stable under other
tests' side effects, or it is a time bomb.** Prefer asserting the invariant
("latest equals the newest published version") over a snapshot of today's data.

## 5. What the black-box suite found that 97 Java tests did not

On its first run, five failures — four of them one bug.

A bare catch-all in the exception handler:

```java
@ExceptionHandler(Exception.class)   // more specific than nothing...
```

...outranks Spring's own `DefaultHandlerExceptionResolver`, so it silently
swallowed the framework's exceptions:

| Client mistake | Correct | Actual |
|----------------|---------|--------|
| malformed JSON body | 400 | **500** |
| wrong `Content-Type` | 415 | **500** |
| unknown URL | 404 | **500** |
| wrong HTTP method | 405 | **500** |

Every client mistake was reported as a server fault. That is not cosmetic:

- it **pages the on-call engineer** for somebody's typo,
- it makes **5xx alerting useless**, because the baseline is full of noise,
- it tells the caller **"retry later"** when retrying can never help.

The Java tests could not have caught it. They only ever sent requests the
controllers were written to accept. You have to be *outside* the application
to send a request it has no handler for.

The fix was to extend `ResponseEntityExceptionHandler`, restoring precedence
to Spring's own handlers while keeping every response in problem+json shape.

**This is the honest argument for a separate black-box suite** — not "more
tests is better", but "a test that shares the application's assumptions cannot
find a bug in those assumptions".

## 6. Assertions that say something useful

A bare `assert response.status_code == 200` tells you nothing when it fails.
Every helper in `framework/assertions.py` dumps the request id, status,
latency and body, so a CI log is enough to diagnose without re-running:

```
Expected HTTP 200, got 502
    GET http://localhost:8081/api/v1/tools/data-validator-a1b2/versions/3.0/artifact
    status    : 502
    requestId : 5b127445-6ff7-4946-921f-8bac30c65b34
    latency   : 12 ms
    body      : {"type":".../artifact-missing","title":"Artifact Missing From Store",...}
```

That `requestId` appears in the service's own access log. Copy, grep, done.

Two assertions are worth calling out:

- **`assert_no_artifact_leaked`** — an error response must never carry
  `artifactPath` or `checksumSha256`. This is what actually enforces "no
  silent fallback": if a 404 body carried coordinates, someone would use them.
- **`assert_bytes_match`** — the strongest assertion available. Not "a
  download happened", but "these are byte-for-byte the bytes we uploaded",
  with both SHA-256s printed on failure.

Errors are asserted on the machine-readable `type` URI, never on the English
`detail` text — which is the entire reason the service speaks RFC 7807. The
wording can be improved without breaking a single test.

## 7. Markers

```bash
pytest -m smoke              # is the platform usable at all?  (~1 s)
pytest -m negative           # error paths and edge cases
pytest -m artifact           # anything moving real bytes
pytest -m "not slow"         # skip multi-round-trip scenarios
```

`smoke` exists so CI can fail in one second on a dead service instead of
discovering it three minutes into the full suite. That tiering is the same
idea as the surefire/failsafe split, applied one level up — and it is what
Phase 6 will exploit to cut pipeline time.

## 8. Running it

```bash
# needs a running platform
docker compose -f docker/docker-compose.yml up -d
cd backend && ./mvnw spring-boot:run

# then, in another terminal
./scripts/run-integration-tests.sh
./scripts/run-integration-tests.sh -m smoke
BASE_URL=http://staging:8081 ./scripts/run-integration-tests.sh
```

Produces `integration-tests/reports/junit.xml` (what CI parses into a test
report) and `report.html` (the human-readable artefact you attach to a build).

Dependencies are **pinned exactly**, not with `>=`. The same discipline this
platform teaches for artifacts applies to a test framework's own dependencies:
a test run that cannot be reproduced cannot be trusted.

## 9. Known gaps, kept visible

`test_unauthenticated_write_is_rejected` is marked `xfail`. Authentication
arrives in Phase 9; the test is written now and left in the report as a known
gap rather than deleted.

**A missing test looks identical to a passing one. An xfail does not.** It
shows up in every run's summary with its reason attached, so the gap stays
in front of whoever reads the output — and the day auth lands, it goes green
on its own and `strict=False` stops it from being forgotten.
