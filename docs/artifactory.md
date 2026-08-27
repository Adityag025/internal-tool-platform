# Artifact repository & JFrog Artifactory

## 1. What an artifact repository actually is

A **build** produces a file. A **source repository** (git) is the wrong place
for it: git stores diffs of text, artifacts are opaque binaries, and committing
them makes clones enormous and history useless.

An **artifact repository** is purpose-built storage for build outputs. It
provides four things git cannot:

1. **Addressing** — a globally unique coordinate per build
2. **Immutability** — a published coordinate never changes content
3. **Integrity** — a checksum recorded at publish time
4. **Lifecycle** — promotion, deprecation, revocation, retention policies

JFrog Artifactory, Nexus, AWS CodeArtifact, GitHub Packages, and Docker
registries are all the same idea with different packaging.

## 2. Artifact coordinates

A coordinate is the address of exactly one build. Every ecosystem spells it
differently:

| Ecosystem | Coordinate |
|-----------|------------|
| Maven | `com.acme:data-validator:1.2` |
| Docker | `registry.acme.io/tools/data-validator:1.2` |
| npm | `@acme/data-validator@1.2.0` |
| **This platform** | `internal-tools-local/data-validator/1.2/data-validator-1.2.jar` |

Ours is `repository / tool / version / filename`. The version appears in both
the directory and the filename on purpose: a jar that gets copied out of the
repository still says what it is.

```
internal-tools-local/
└── data-validator/
    ├── 1.0/data-validator-1.0.jar
    ├── 1.1/data-validator-1.1.jar
    ├── 1.2/data-validator-1.2.jar
    └── 2.0/data-validator-2.0.jar
```

## 3. Immutability

**A published coordinate never changes content. Ever.**

The platform enforces this in three independent places, which is not
redundancy — each one closes a different hole:

| Layer | Mechanism | Closes |
|-------|-----------|--------|
| Database | `UNIQUE (tool_id, version)` | two concurrent publishes of the same version |
| Domain | `ToolVersion.sealWith()` throws if a checksum is already set | re-uploading bytes for a sealed version |
| Store | `FilesystemArtifactStore` refuses to overwrite; Artifactory rejects redeploy | anything that reaches storage by another route |

**Why it matters.** "We tested 1.2" is only meaningful if 1.2 cannot change.
If it could, then a test result, an audit record, a rollback target, and a
reproducible build all quietly become guesses. Mutable artifacts are how you
get "it worked in staging" with no way to find out why.

`SNAPSHOT` versions are the deliberate exception: explicitly mutable,
explicitly not for release consumption.

## 4. Checksums

Every artifact carries a **SHA-256**, computed at upload and stored in the
registry alongside the coordinates.

It is used at three moments:

1. **On upload** — Artifactory recomputes the digest and rejects the deploy if
   it disagrees with the `X-Checksum-Sha256` header. Corruption in transit is
   caught immediately, not by a consumer next week.
2. **On download** — this platform re-hashes the bytes the store returned and
   compares them with the registry's record. A mismatch is **502**, never a
   served file.
3. **By the consumer** — the SHA-256 comes back in the `ETag` and the
   `X-Artifact-Sha256` header so the client can verify independently.

Trusting the transport is not the same as verifying the content.

## 5. Promotion

**Promotion moves the same bytes through a lifecycle. It never rebuilds.**

```
DRAFT ──────► PUBLISHED ◄────► DEPRECATED
  │               │                │
  └───────────────┴────────────────┴──────► REVOKED  (terminal)
```

| Status | Meaning | Download |
|--------|---------|----------|
| `DRAFT` | uploaded, not cleared for consumers | by explicit pin only |
| `PUBLISHED` | cleared for general use | 200 |
| `DEPRECATED` | still works, please migrate | 200 + `Deprecation: true` |
| `REVOKED` | pulled (CVE, bad build) | **410 Gone** |

Two rules are enforced by `ToolRegistryService.promote`:

- **`DRAFT → PUBLISHED` requires an uploaded artifact.** "PUBLISHED" can never
  mean "a row exists but there is nothing to download".
- **`REVOKED` is terminal.** Un-revoking would let a consumer who correctly
  stopped using an artifact be silently handed it again.

Why promotion instead of rebuilding? A rebuild produces *different bytes* —
different timestamps, a different dependency resolved, a different compiler
patch. So "the thing we tested" and "the thing we released" would no longer be
the same artifact, and every test result recorded before the rebuild would be
worthless. Promotion is the only way `build once, deploy everywhere` is true
rather than aspirational.

## 6. The port/adapter split

```
       ArtifactService
             │
             ▼
      ArtifactStore  (interface — the PORT)
        ╱          ╲
FilesystemArtifactStore   ArtifactoryArtifactStore
   (dev, CI, tests)          (the real repository)
```

Selected at startup by `platform.artifacts.store`. Nothing in the service
layer knows which is active.

This is not abstraction for its own sake. It buys three concrete things:

- **Integration tests need no Artifactory** — `ArtifactDistributionIT` exercises
  the entire distribution path against a temp directory.
- **CI does not depend on an external service being up** — an Artifactory
  outage cannot turn every pull request red.
- **The repository manager is replaceable** — S3, Nexus, or CodeArtifact is a
  new adapter, not a refactor.

## 7. Running Artifactory locally

It is a 3.8 GB image that wants ~4 GB of RAM and takes 2-4 minutes to boot, so
it lives behind a **compose profile** and is off by default:

```bash
# everyday work - PostgreSQL only
docker compose -f docker/docker-compose.yml up -d

# when you actually want the real repository
docker compose -f docker/docker-compose.yml --profile artifactory up -d
```

UI: <http://localhost:8182>  (default credentials `admin` / `password`)

Create the repository and point the app at it:

```bash
./scripts/setup-artifactory.sh          # creates internal-tools-local

export ARTIFACT_STORE=artifactory
export ARTIFACTORY_URL=http://localhost:8182
export ARTIFACTORY_REPO=internal-tools-local
export ARTIFACTORY_USERNAME=admin
export ARTIFACTORY_PASSWORD='...'       # never committed
cd backend && ./mvnw spring-boot:run
```

The app logs which adapter it selected at startup:

```
artifact.store.selected type=ARTIFACTORY target=artifactory:http://localhost:8182/internal-tools-local
```

## 8. The three REST calls this needs

| Purpose | Call |
|---------|------|
| Deploy | `PUT /artifactory/{repo}/{path}` + `X-Checksum-Sha256` header |
| Download | `GET /artifactory/{repo}/{path}` |
| Metadata | `GET /artifactory/api/storage/{repo}/{path}` |

That is the entire integration surface. Artifactory has a very large API; a
platform needs almost none of it.

## 9. Credentials

No credential has a default value:

```yaml
artifactory:
  base-url: ${ARTIFACTORY_URL:}
  username: ${ARTIFACTORY_USERNAME:}
  password: ${ARTIFACTORY_PASSWORD:}
```

If they are missing, `ArtifactStoreConfig` throws at startup with an explicit
message. **A default password is worse than no password** — it works right up
until it is the one running in production.

| Environment | Source |
|-------------|--------|
| Local | `.env`, gitignored |
| CI | GitHub Actions Secrets, injected only into the step that publishes |
| AWS | SSM Parameter Store / Secrets Manager via the ECS task definition |

In a real deployment the password is an **access token** (scoped, revocable,
expiring), not a user password, and the URL is `https`.

## 10. A note on running the real thing

Artifactory OSS 7.x is a **multi-service application in one container** —
router, access, event, frontend, and the artifactory service itself. On a
machine with limited free RAM it can fail to boot in ways that look mysterious
until you read the logs. Three failures encountered here, in order:

1. `Failed resolving master key ... master.key does not exist` — a fresh volume
   has no master key. Fixed with `JF_SHARED_SECURITY_MASTERKEY`.
2. `Failed resolving join key ... join.key does not exist` — the internal
   services authenticate to each other with a second key. Fixed with
   `JF_SHARED_SECURITY_JOINKEY`. **Both are required**; supplying only one
   gets you a FATAL several minutes into boot.
3. `Failed joining Access: Access Service ping failed; context deadline
   exceeded` — the Access service did not start within the router's timeout.
   This is resource starvation, not configuration. Artifactory wants ~4 GB of
   RAM to itself.

**This is exactly why the `ArtifactStore` port exists.** The platform's entire
distribution path — upload, checksum sealing, promotion, verified download,
client resolution — is tested and demonstrable without Artifactory running.
The Artifactory adapter is verified separately against a mock HTTP server
(`ArtifactoryArtifactStoreTest`), which asserts the exact requests it issues.

That test earned its keep immediately: it caught a bug that a live Artifactory
would only have revealed as a confusing 404. The natural way to write the call,

```java
.uri("/artifactory/{repo}/{path}", repository, path)
```

is wrong. A URI template variable is one path *segment*, so the slashes in
`data-validator/1.2/data-validator-1.2.jar` are percent-encoded to `%2F` and
Artifactory sees a single flat filename — the entire repository layout
collapses into the repo root. The fix is to split into segments:

```java
builder.pathSegment("artifactory", repository).pathSegment(path.split("/")).build()
```

A protocol-level test found that in milliseconds, offline. A live integration
test would have found it in minutes, and only if the container booted.
