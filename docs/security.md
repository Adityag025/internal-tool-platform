# Security

## 1. The threat model, stated plainly

An internal platform on a trusted network. The realistic threats, in order:

1. **An accidental write.** A script pointed at the wrong environment
   publishes or re-pins something. Far and away the most likely.
2. **A curious insider.** Someone reads what they should not, or changes a
   pin they do not own.
3. **A leaked credential in git or a log.** The classic.
4. **A tampered artifact.** Bytes changed between publish and download.

Reading which versions exist is not sensitive and every consumer needs it
constantly. **Writing** is what matters: publishing an artifact or re-pinning
a client changes what runs on other people's machines.

Hence: **reads open, writes authenticated.**

## 2. Authentication

`X-API-Key` on every `POST`/`PUT`/`DELETE`.

### Why an API key and not OAuth2/JWT

The callers are machines — a CI pipeline publishing a build, an operator
changing a pin. There is no user to redirect to a login page and no session to
maintain, so an authorization-code flow buys nothing but ceremony. A key in a
secret store is the right size of solution.

A real deployment would add per-caller keys (so they can be revoked and
attributed individually), an expiry, and scopes so the CI token can publish
but not re-pin a client. Those are additive; the shape does not change.

### Details that matter

**Constant-time comparison.**

```java
MessageDigest.isEqual(presented.getBytes(UTF_8), expectedKey)
```

`String.equals` short-circuits on the first differing byte, so how long it
takes leaks how many leading characters were correct. That is a timing oracle
— an attacker recovers the key one character at a time.

**A wrong key is rejected identically to no key.** Same status, same body. A
different response would confirm that a key format or prefix was right.

**Errors keep the problem+json contract.**

```json
{"type":"https://platform.acme.internal/errors/unauthorized",
 "title":"Unauthorized","status":401,
 "detail":"This operation requires the X-API-Key header"}
```

Plus `WWW-Authenticate: ApiKey realm="tool-platform"` (RFC 7235), so a
well-behaved client is told *how* to authenticate. Without a custom entry
point, Spring Security returns an HTML error page and breaks the contract
every client and the whole test suite assert on.

**Metrics are not public.** `/actuator/prometheus` needs the key: metric names
enumerate internal endpoints and reveal traffic patterns. Health stays public,
because orchestrators cannot authenticate.

### It can be switched off, and that is loud

With no key configured the service runs unauthenticated — because forcing a
credential into every local `curl` gets the whole mechanism disabled by
someone in a hurry. But it prints this on every start-up:

```
==============================================================================
SECURITY: platform.security.api-key is not set - WRITES ARE UNAUTHENTICATED.
          Anyone who can reach this service can publish artifacts and
          re-pin clients. Acceptable locally; never in a shared environment.
          Set API_KEY (env) to enable authentication.
==============================================================================
```

**A silent insecure default is how you end up in production without noticing.
A loud one is a decision.**

## 3. Secret management

No credential appears in any file in this repository. The application code is
**identical** in all three environments; only the injection mechanism differs.

| Environment | Mechanism | Scope |
|-------------|-----------|-------|
| Local | `.env`, gitignored; `.env.example` is the committed template | the developer's machine |
| CI | GitHub Actions Secrets | injected into **one step's** environment |
| AWS | SSM Parameter Store (`SecureString`, KMS-encrypted) | fetched by the ECS agent at start-up |

```yaml
password: ${DB_PASSWORD:localdev}      # env var, local-dev default only
api-key:  ${API_KEY:}                  # NO default - absent means "off", loudly
```

**Credentials with no default at all:** `ArtifactStoreConfig` refuses to start
if `ARTIFACTORY_USERNAME`/`PASSWORD` are missing while the Artifactory adapter
is selected. A default password is worse than no password — it works right up
until it is the one in production.

**And in CI:**

```yaml
- name: Publish to Artifactory
  env:
    ARTIFACTORY_PASSWORD: ${{ secrets.ARTIFACTORY_PASSWORD }}
```

Scoped to that single step, never written to disk, never echoed, never passed
as a command-line argument — `argv` is readable by other processes.

**And to AWS: no long-lived keys at all.** The deploy workflow authenticates
by OIDC — a short-lived token exchanged for temporary credentials. There is no
`AWS_SECRET_ACCESS_KEY` to leak, rotate, or find in a git history.

## 4. Input validation and injection

| Vector | Defence |
|--------|---------|
| SQL injection | Spring Data JPA parameterises everything; no string-concatenated SQL exists |
| Path traversal | Slug pattern on tool names, a strict relative-path pattern on `artifactPath`, **and** a normalise-and-check in `FilesystemArtifactStore` |
| XSS in the dashboard | every interpolated value passes through `escapeHtml`; no `innerHTML` of raw server data |
| Oversized uploads | `platform.artifacts.max-artifact-bytes`, default 100 MB |
| CSRF | not applicable — stateless, authenticated by an explicit header a cross-site request cannot set |
| CORS | a configured allow-list, never `*` |

Path traversal is defended **twice**, deliberately. Request validation gives a
good error message; the check inside the store gives a guarantee, because that
method is reachable from anywhere — not only from a validated request.

## 5. Supply chain

- **Dependencies pinned exactly** — Maven via the Boot BOM, Python with `==`,
  npm with exact versions. A build you cannot reproduce is a build you cannot
  audit.
- **SHA-256 on every artifact**, verified on the way out. A mismatch is a 502
  and the bytes are **not served**.
- **Immutable tags** — in the registry, and `--image-tag-mutability IMMUTABLE`
  on ECR.
- **`scanOnPush`** on the ECR repository.
- **Non-root container**, JRE-only runtime image: 673 MB → 376 MB, and every
  megabyte removed is attack surface removed.

## 6. What is deliberately not here

- **Per-caller keys, scopes, expiry.** One shared key is honest for a learning
  project; the extension is obvious and additive.
- **Rate limiting.** Belongs at the ingress (ALB/API Gateway), not in
  application code.
- **Audit log to durable storage.** Every state change is logged with the old
  and new value, but to stdout — an audit trail you can delete is not one.
- **TLS.** Terminated at the load balancer in the AWS design; the service
  speaks plain HTTP behind it.
