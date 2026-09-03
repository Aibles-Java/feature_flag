# 0030 — Scan the image in CI (not just publish), and patch the base-image OpenSSL CVE

**Branch:** `fix/trivy-ci-scan` (off `origin/develop`, at `4a44745`). Two changes in one PR.
Follow-up to [[0023-trivy-action-pin-and-postgres-cve-bump]] (same Trivy gate) and
[[0025-gate-publish-behind-sonar]] (the `!cancelled()` needs-pattern reused here).

## Trigger

The `develop` publish job failed at the **Trivy scan** step (run 33088590577): base image
`eclipse-temurin:21-jre-alpine` (alpine 3.24.1) shipped OpenSSL `3.5.7-r0` with 3 HIGH
findings — all **CVE-2026-14456** (`openssl`/`libssl3`/`libcrypto3`, "DoS via unbounded
memory growth in QUIC server"), **fixed in `3.5.8-r0`**. `app/app.jar` scanned clean (0).
Not reachable by the app (a JVM service uses JSSE, not system OpenSSL, and runs no QUIC
server) — but per 0023's rule, `ignore-unfixed: true` means a *fixed* HIGH ⇒ **patch, never
`.trivyignore`** (that allowlist is for unfixed/unreachable only).

## What was decided

**1. Patch the base OS at build time.** Added `RUN apk upgrade --no-cache` to the runtime
stage of `Dockerfile` (as root, before the `USER spring` drop). The floating `:21-jre-alpine`
tag lags apk security updates, so fixed CVEs sit in the base layer until the tag rebuilds;
`apk upgrade` pulls them now. This is the general fix — it clears the next OS CVE too, not
just this one.

**2. Move image scanning left into CI.** Trivy previously ran ONLY inside `publish`, which
`needs: [test, sonar]` and only runs on push to develop/main/tags → a vuln was caught **after
merge**, never on the PR. Added a new **`scan`** job: `needs: test`, `runs-on: ubuntu-latest`,
builds the image locally (`push: false, load: true, tags: feature_flag:scan`) and Trivy-scans
it with the **same policy + `.trivyignore`** as publish. It runs on **PRs and pushes**
(`if: github.event_name != 'workflow_dispatch'` — a redeploy dispatch builds nothing to scan),
needs no secrets so it runs on fork PRs, and uses `cache-to: type=gha,mode=max` to warm the
shared layer cache so publish's later build is a cache hit.

**3. Keep publish's own Trivy scan as the final gate.** Did NOT remove it — defense in depth:
`scan` = time-of-check (PR), publish's scan = time-of-ship. A base-image CVE disclosed
*between* merge and release is still blocked from reaching GHCR. Base-image CVEs appear on a
clock, so this gap is real.

## The `publish` gate change (reuses the 0025 pattern)

`publish` now `needs: [test, sonar, scan]` and its `if:` adds `&& needs.scan.result ==
'success'`. Unlike `sonar` (which can be *skipped* on tags/fork-PRs → allowed via
`|| == 'skipped'`), **`scan` never skips on a push** (only on dispatch, where publish is
itself gated out), so it is required to be a strict `success`. The `!cancelled()` +
explicit `needs.test.result == 'success'` scaffolding from 0025 stays.

## Verification

Docker daemon was down locally (no colima) → no local build; YAML validated (ruby), GHA
`if:` logic reasoned through. **Authoritative proof is CI itself:** the `scan` job on the PR
must go green (apk upgrade clears CVE-2026-14456), and the develop publish must succeed after
merge. Matrix intent: PR ✅ scan runs+gates; develop push ✅ scan+sonar both gate publish;
tag ✅ sonar skipped / scan runs; dispatch ✅ scan skipped, publish already gated out.

**Pattern:** image/container vuln scanning is a **CI quality gate** — run it on PRs (fail
fast, block merges), not only in the CD publish job. But keep a ship-time scan too; the two
cover different risk windows. For a base-image OS CVE with a fix, `apk upgrade --no-cache`
beats a per-CVE `.trivyignore` entry.
