# Development roadmap

Each phase is independently runnable and independently verifiable. Nothing
starts until the previous phase has been demonstrated working.

| Phase | Deliverable | Concept it teaches | Status |
|-------|-------------|--------------------|--------|
| **1** | Tool Registry Service: Spring Boot + PostgreSQL, tools & versions, exact-version resolution, RFC 7807 errors, Flyway, health endpoint, unit + slice + Testcontainers tests | REST design, layering, immutability at the DB level, semantic versioning, fast/slow test split | **DONE** |
| **2** | Clients & version pinning: `clients`, `client_tool_configuration`, `PUT/GET/DELETE /clients/{client}/tools/{tool}/version`, opt-in `latest`, 410 for revoked | Version pinning, per-client configuration, rollback as a config change, referential integrity | **DONE** |
| **3** | Artifactory integration: Artifactory in Docker, upload on publish, `GET .../artifact` streaming download, SHA-256 verification, promotion (`DRAFT → PUBLISHED → DEPRECATED → REVOKED`) | Artifact repositories, coordinates, checksums, immutability, promotion | pending |
| **4** | Python pytest data-driven integration framework: JSON/YAML test data, fixtures, parameterisation, negative + edge cases, HTML report | Data-driven testing, black-box API testing, test data as configuration | pending |
| **5** | **Baseline** GitHub Actions pipeline, deliberately unoptimised. Measure wall-clock. | CI stages, fail-fast, what "slow" actually looks like | pending |
| **6** | **Optimised** pipeline + measured before/after comparison | Caching, parallel jobs, test tiering, artifact reuse — pipeline optimisation with real numbers | pending |
| **7** | Dockerfile (multi-stage, layer-cached), full Compose stack, small TypeScript client | Containerisation, service discovery, image layering | pending |
| **8** | AWS: ECR + ECS Fargate deployment guide, then RDS as an upgrade path | Cloud deployment, task definitions, secrets in the cloud | pending |
| **9** | Observability, security hardening, full interview pack, resume mapping | Release engineering as a discipline | pending |

## Why this order

- **Registry before Artifactory** — the metadata model is the hard design
  problem. Artifactory is a client of it, not the other way round.
- **Tests before pipeline** — a pipeline is only worth optimising once it has
  something real and slow to run.
- **Baseline before optimisation** — you cannot claim an improvement you did
  not measure. Phase 5 exists purely to produce the "before" number.
- **Local before AWS** — AWS adds cost and latency to every debugging loop.
