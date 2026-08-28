# Observability

Three signals, each answering a different question. The trick is not
collecting more of them — it is knowing which one to reach for.

| Signal | Answers | Cost | Cardinality |
|--------|---------|------|-------------|
| **Logs** | "what exactly happened in this one case?" | cheap to write, expensive to search | unlimited |
| **Metrics** | "how often, how fast, how bad, right now?" | cheap to query, costly per series | must be bounded |
| **Health** | "should traffic go here?" | trivial | none |

## 1. Logs

Every request produces one structured access line and carries a correlation id.

```
07:59:58.921 INFO [fbb7e9d4-...] access - http method=GET
  path=/api/v1/clients/client-a/tools/data-validator/version status=200
  latencyMs=8 client=pytest-integration-suite
```

`RequestLoggingFilter` puts a `requestId` into the SLF4J **MDC**, so every log
line in that request carries it automatically without being threaded through
method signatures. The same id goes back in the `X-Request-Id` response header
and inside every problem+json error body.

That closes a loop that is normally painful: a user pastes an error into a
ticket, and the id in it is the exact string to grep for.

### Domain events, not just HTTP

```
version.published      tool=data-validator version=1.2 path=... status=PUBLISHED
version.resolved       tool=data-validator version=1.2 status=PUBLISHED latencyMs=3
client.config.changed  client=client-c tool=data-validator from=[PINNED:2.0] to=[PINNED:1.2]
artifact.uploaded      tool=... version=1.2 bytes=57611846 sha256=3825f489...
artifact.delivered     client=client-a tool=... selector=PINNED version=1.0 sha256=...
version.promoted       tool=... version=1.2 from=DRAFT to=PUBLISHED
auth.rejected          reason=bad-api-key method=POST path=/api/v1/tools
```

Two deliberate properties:

- **`key=value` pairs, not prose.** `version.resolved tool=x version=1.2` is
  greppable and parseable into fields. "Resolved version 1.2 of tool x for
  client a" is not.
- **The event name comes first**, so `grep 'client.config.changed'` finds every
  pin change in the system's history. `client.config.changed` logs both the
  old and new value, which is what makes "who moved client-c to 2.0?"
  answerable at all.

**What is never logged:** the API key, even on rejection. `auth.rejected` logs
the method and path and stops. Logging a rejected credential is how secrets
end up in a log aggregator half the company can read — and a rejected
credential is often a *valid* credential for something else.

## 2. Metrics

Micrometer, exposed at `/actuator/prometheus`.

| Metric | Type | Tags | Why |
|--------|------|------|-----|
| `toolplatform_version_resolutions_total` | counter | tool, selector, outcome | who is pinned vs floating, and how often resolution fails |
| `toolplatform_artifact_download_seconds` | timer + p50/p95/p99 | tool, outcome | download latency, with the tail visible |
| `toolplatform_artifact_bytes_served_total` | counter | tool | egress volume per tool |
| `toolplatform_version_published_total` | counter | tool | release frequency |
| `toolplatform_artifact_checksum_mismatch_total` | counter | tool | **integrity failures** |
| `toolplatform_auth_rejected_total` | counter | — | misconfigured client, or a probe |

Live from the running service:

```
toolplatform_version_published_total{application="tool-registry",environment="local",tool="..."} 18.0
toolplatform_artifact_download_seconds_count{outcome="success",tool="..."} 14
toolplatform_artifact_download_seconds{outcome="success",quantile="0.99"} 3.52256E-4
toolplatform_artifact_download_seconds_count{outcome="revoked",tool="..."} 1
```

### Cardinality is the thing to get right

A Prometheus time series exists for **every unique combination of tag values**,
and each one costs memory in the process and in the scraper. So these metrics
are tagged by:

- **tool** — a handful, bounded
- **outcome** — a fixed enum
- **selector** — two values

and never by **version**, **client**, or **request id**. Tagging by version
would create a new series on every release, forever: the classic cardinality
explosion that takes a monitoring stack down. Per-version detail is a question
for logs, which are cheap to write and searched on demand.

`management.metrics.tags` adds `application` and `environment` to every
metric, so one dashboard separates environments and an alert can name the
service that fired it.

### The one metric that should page someone

```
toolplatform_artifact_checksum_mismatch_total
```

Correct alert threshold: **greater than zero**. It means stored bytes no
longer hash to what was published — corruption or tampering. It has its own
counter precisely so it is not buried inside a general download-error rate,
where a handful of 502s a day would hide it.

Sensible alerts on the rest:

| Alert | Condition |
|-------|-----------|
| Integrity failure | `checksum_mismatch_total > 0` |
| Registry/store drift | `rate(version_resolutions_total{outcome="not_found"}[5m])` climbing |
| Slow downloads | `p99 artifact_download_seconds > 2s` for 10m |
| Credential problem | `rate(auth_rejected_total[5m]) > 0` sustained |

## 3. Health

```
/actuator/health              full detail
/actuator/health/liveness     am I alive? -> restart me if not
/actuator/health/readiness    can I serve? -> send/withhold traffic
```

**Liveness and readiness are different questions and wiring them together is a
classic outage.** If readiness is used as liveness, a database blip restarts
every instance at once — turning a recoverable dependency failure into a full
outage. Liveness should fail only when the process is unrecoverable.

The health endpoint reports both dependencies:

```json
{"status":"UP","components":{
  "db":{"status":"UP","details":{"database":"PostgreSQL"}},
  "artifactStore":{"status":"UP","details":{"store":"filesystem:/var/lib/..."}}}}
```

`ArtifactStoreHealthIndicator` probes a path that will never exist — proving
the store is reachable and answering, without depending on any content. **A
health check that only proves the JVM is running is not a health check**: this
service can be perfectly alive and completely useless because it cannot reach
its artifact store.

Health is public even when authentication is on, because load balancers, ECS
and Kubernetes cannot present a credential.

## 4. What is deliberately not here

Honest gaps, not oversights:

- **Distributed tracing.** One service and one database; a trace would show
  what the access log already shows. It earns its place at the third hop.
- **Log aggregation.** Logs go to stdout, which is correct for a container —
  collection is the platform's job (CloudWatch via the `awslogs` driver in the
  ECS task definition).
- **JSON log encoding.** Human-readable locally is worth more than
  machine-readable; a production profile would switch the Logback encoder.
