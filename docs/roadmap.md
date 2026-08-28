# Development roadmap

Each phase is independently runnable and independently verifiable. Nothing
starts until the previous phase has been demonstrated working.

| Phase | Deliverable | Concept it teaches | Status |
|-------|-------------|--------------------|--------|
| **1** | Tool Registry Service: Spring Boot + PostgreSQL, tools & versions, exact-version resolution, RFC 7807 errors, Flyway, health endpoint, unit + slice + Testcontainers tests | REST design, layering, immutability at the DB level, semantic versioning, fast/slow test split | **DONE** |
| **2** | Clients & version pinning: `clients`, `client_tool_configuration`, `PUT/GET/DELETE /clients/{client}/tools/{tool}/version`, opt-in `latest`, 410 for revoked | Version pinning, per-client configuration, rollback as a config change, referential integrity | **DONE** |
| **3** | Artifact distribution: `ArtifactStore` port with filesystem + Artifactory adapters, upload/download of real bytes, SHA-256 verified on the way out, promotion state machine, client-facing download | Artifact repositories, coordinates, checksums, immutability, promotion, ports & adapters | **DONE** |
| **4** | Python pytest data-driven framework: JSON/YAML case files, reusable fixtures + assertions, run-scoped isolation, negative/edge cases, JUnit + HTML reports | Data-driven testing, black-box API testing, test data as executable specification | **DONE** |
| **5** | **Baseline** GitHub Actions pipeline, deliberately unoptimised, measured over repeated runs (201 s / 250 s, mean ~225 s) | CI stages, fail-fast, secret scoping, measuring before optimising | **DONE** |
| **6** | **Optimised** pipeline + measured before/after comparison | Caching, parallel jobs, test tiering, artifact reuse — pipeline optimisation with real numbers | pending |
| **7** | Multi-stage Dockerfile (673 MB → 376 MB, non-root), full Compose stack wired by service name, small TypeScript CLI + dashboard sharing one typed API contract | Containerisation, service discovery, image layering, volume ownership, CORS | **DONE** |
| **8** | AWS: ECS Fargate task definition, scoped IAM, OIDC-authenticated CD workflow, idempotent deploy script, RDS path, costs and teardown. **Written and validated, not provisioned.** | Cloud deployment, immutable task revisions, keyless CI auth, least privilege, cost awareness | **DONE** |
| **9** | Prometheus metrics with bounded cardinality, API-key auth for writes (the Phase 4 `xfail` now passes), threat model, full interview bank, resume mapping | Release engineering as a discipline, and describing your own work honestly | **DONE** |

## Why this order

- **Registry before Artifactory** — the metadata model is the hard design
  problem. Artifactory is a client of it, not the other way round.
- **Tests before pipeline** — a pipeline is only worth optimising once it has
  something real and slow to run.
- **Baseline before optimisation** — you cannot claim an improvement you did
  not measure. Phase 5 exists purely to produce the "before" number.
- **Local before AWS** — AWS adds cost and latency to every debugging loop.
