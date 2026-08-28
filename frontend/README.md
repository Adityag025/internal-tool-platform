# TypeScript client

Two consumers of the platform, sharing one typed API client. Deliberately
small: no framework, no bundler, no state library.

```
src/api.ts        the typed contract - shared, written once
src/cli.ts        Node CLI: the client a real consumer would use
src/dashboard.ts  browser UI: pick a tool, pick a version, request it
index.html        the whole UI, ~90 lines including CSS
```

## Why TypeScript here at all

Not for taste. `api.ts` is the API contract expressed as types, so if the
service renames `resolvedVersion`, **both clients fail to compile** instead of
rendering `undefined` into a page or writing a broken file to disk. That is
the entire justification, and it is why the compiler options are strict
(`strict`, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`).

It earned that immediately: the first build failed because the CLI used
`node:fs` and `process` without Node's type definitions being installed.

## Why no React

Rendering two dropdowns and a table does not need a framework. React would add
hundreds of transitive dependencies to this project's supply chain for
something the DOM does in forty lines — and a tool-distribution platform that
lectures about artifact provenance should not be careless about its own.

## Build and run

```bash
npm install
npm run build          # tsc -> dist/
```

### CLI

```bash
node dist/cli.js --list
node dist/cli.js --tool data-validator --list
node dist/cli.js --tool data-validator --version 1.2
node dist/cli.js --client client-a --tool data-validator   # names NO version
```

The last form is the point of the platform. The consumer does not know or care
which version it is on; that is a property of the platform's configuration, so
rolling it back changes nothing here.

Every download is re-hashed locally and the file is **not written** if the
digest disagrees with the server's:

```
wrote    tool-registry-0.1.999.jar  (57611846 bytes)
version  0.1.999
sha256   3825f489fbfbd36d5ea9dfde4d1d473214c65fe3fc341c1db943629c2852098e  verified
```

Errors surface the platform's problem+json contract, including the request id
you can grep for in the service log:

```
error: Version '999.0' of tool 'data-validator' does not exist
  kind      : not-found
  status    : 404
  requestId : 59f35bb0-49dd-403f-a372-cc13b189e627
  hint      : run with --list to see which versions exist
```

### Dashboard

Served by the `frontend` container on <http://localhost:3000>:

```bash
npm run build
docker compose -f ../docker/docker-compose.yml up -d --wait
```

Or without Docker: `npm run serve` (Python's http.server on port 3000).

It must be served over HTTP, not opened as a `file://` URL — browsers refuse
to load ES modules from the filesystem, and `crypto.subtle` (used for the
checksum) is only available in a secure context.

Point it at another instance with `?api=http://host:8081`.

The browser recomputes the SHA-256 of the downloaded bytes and compares it
with the server's header, exactly as the CLI does. A `REVOKED` version is
listed but not selectable — hiding it would leave a user wondering where their
version went, rather than telling them it was withdrawn.
