---
name: gha-layer-cache-freezes-apk-upgrade
description: cache-from type=gha reuses the `RUN apk upgrade` layer for as long as its instruction text is unchanged, freezing OS packages and silently defeating the base-image patching added in 0030 — bust it with a per-run build-arg
metadata:
  type: convention
---

# GHA layer cache silently freezes `apk upgrade`

Decision [[0030-scan-image-in-ci-and-patch-base-openssl]] added `RUN apk upgrade --no-cache` to
the Dockerfile's runtime stage so that OS fixes land at build time, because the floating
`:21-jre-alpine` tag lags apk. That works on a cold build and **stops working the moment the
layer is cached**: `cache-from: type=gha` keys the layer on the *instruction text*, which never
changes, so the packages stay pinned to whatever was current when the cache entry was written.

`--no-cache` in `apk upgrade --no-cache` is apk's own index cache, not Docker's. It does nothing
about this.

## How it showed up (2026-09-03)

The Trivy gate on `develop` failed with `libexpat 2.8.3-r0` (CVE-2026-66046, CVE-2026-76641).
Looked like "no fix available upstream yet". It wasn't:

```
$ docker run --rm eclipse-temurin:21-jre-alpine sh -c 'apk info -vv | grep -i expat'
libexpat-2.8.3-r0
$ docker run --rm eclipse-temurin:21-jre-alpine sh -c 'apk upgrade --no-cache >/dev/null; apk info -vv | grep -i expat'
libexpat-2.8.4-r0          # the fix, already in alpine/v3.24/main
```

The Dockerfile already fixed it. CI just never re-ran the layer. Nothing in the scan output
distinguishes "no fix upstream" from "your cache is stale" — **always check `apk policy <pkg>`
in the base image before reaching for `.trivyignore`.**

## The fix

`ARG APK_EPOCH=0` before the upgrade, referenced inside the `RUN` so it participates in the
cache key, and `build-args: APK_EPOCH=${{ github.run_id }}` on **every** `docker/build-push-action`
step — the scan build, the publish build, and the push.

Using the run id rather than a timestamp matters: within one run all three steps resolve to the
same layer, so **the image that ships is byte-for-byte the image Trivy scanned**. A `date +%s`
would give the push a different layer than the scan — a scan gate that no longer guarantees
anything. Across runs the id changes, so packages are re-resolved every time.

Cost is small: the busted layer sits in the runtime stage after `FROM`, so only `apk upgrade` +
`COPY --from=build` + `adduser` re-run. The expensive Maven build stage has its own cache key and
is untouched.

Related: [[0023-trivy-action-pin-and-postgres-cve-bump]] — `ignore-unfixed: true` means every
finding that reaches you *has* a fix, so the answer is always "patch", never "allowlist"; this
convention is about the case where you already patched and the cache hid it.
