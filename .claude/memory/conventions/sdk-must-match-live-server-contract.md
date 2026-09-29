# A client SDK must match the LIVE server contract, not the design doc

**Discovered:** 2026-09-29, feature `client-sdk`.

When building the client SDK, the P2 design (LLD §6.3, threat-model I2) had moved the rollout
`identifier` OFF the URL and onto an `X-Flag-Identifier` **header** to keep PII out of access logs.
But the actual server — `controller/sdk/EvaluationController` — reads
`@RequestParam(required = false) String identifier`, i.e. a **query param**. A first SDK build
followed the design and sent the header; that would have **silently broken rollout in production**
(server ignores the header, treats identifier as absent → fail-open fully-on for partial rollouts).

## Rule

The SDK is a client: it MUST bind to the server's real, deployed contract. Before coding transport,
read the actual controller + response DTO (not just the design docs) and confirm:
- param location (query vs header vs body) and exact names,
- auth header name (here: `X-Environment-Key`),
- response JSON shape and nullability.

Where the design and the live server disagree, either (a) match the server now and record the
design deviation as a documented residual + a planned coordinated change, or (b) change the server
first. Do NOT ship an SDK that assumes an unmerged server change.

## Applied

SDK v1 sends `?identifier=...` (URL-encoded) to match the server; the header move is deferred to a
later phase requiring a coordinated `EvaluationController` change, documented in
`feature-flag-sdk/README.md` (Known deviation) and as a DE-07 access-log residual. See
`decisions/0036-client-sdk-standalone-module.md`.

## Also

Standalone module quirk: the root pom is not an aggregator, so build the SDK with
`./mvnw -f feature-flag-sdk/pom.xml …`, never `-pl feature-flag-sdk` (fails: "Could not find the
selected project in the reactor"). This tripped both a manual run and the first CI job draft.
