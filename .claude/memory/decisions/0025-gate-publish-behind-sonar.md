# 0025 — Gate GHCR publish (and deploy) behind SonarQube

**Branch:** `feature/gate-publish-behind-sonar` (off `origin/develop`). Commit `f6afcec`.
Follow-up to [[0024-merge-sonar-into-ci-gated]] (which merged Sonar into CI but left
`publish`/`deploy` ungated by Sonar).

## What was decided

Added `sonar` to the `publish` job's `needs` in `.github/workflows/workflow.yml`, so a
failed Sonar / Quality Gate now blocks the Docker image from shipping to GHCR (and, via
`deploy`'s `needs: publish`, blocks the deploy too). Previously `publish` only
`needs: test`, so Sonar ran in parallel and never gated the ship path.

## Why the `if:` is non-trivial (the tag-push trap)

`sonar` is **SKIPPED** on tag pushes (its `if` excludes tags — see 0024) and on fork PRs.
In GitHub Actions a **skipped dependency skips the dependent job by default**, so a bare
`needs: [test, sonar]` would skip `publish` on every `v*` release → no image, no deploy.

Fix: the `publish` `if:` uses `!cancelled()` + explicit result checks:

```yaml
needs: [test, sonar]
if: >-
  ${{ !cancelled()
    && needs.test.result == 'success'
    && (needs.sonar.result == 'success' || needs.sonar.result == 'skipped')
    && github.event_name == 'push'
    && (github.ref == 'refs/heads/develop'
      || github.ref == 'refs/heads/main'
      || startsWith(github.ref, 'refs/tags/v')) }}
```

- `!cancelled()` — REQUIRED so the job is still evaluated when a dependency is *skipped*
  (otherwise GitHub auto-skips `publish`). It also drops the implicit "needs succeeded"
  gate, which is why...
- `needs.test.result == 'success'` — must be checked **explicitly** now.
- `needs.sonar.result == 'success' || == 'skipped'` — publish runs when Sonar passes
  (develop/main push) OR is legitimately skipped (tag, fork PR), but is **blocked** on
  `failure`/`cancelled`.
- The `event_name == 'push' && (ref…)` tail is unchanged from before.

## Resulting gate matrix

| Event | test | sonar | publish |
|---|---|---|---|
| push develop | success | success | ✅ |
| push develop | success | **failure** | ❌ blocked |
| push tag `v*` | success | skipped | ✅ (release not blocked) |
| workflow_dispatch | — | skipped | ✅ (manual redeploy) |

`deploy` (`needs: publish`, `if: always() && needs.publish.result == 'success'`) inherits
the gate: Sonar-fail on develop → publish skipped → deploy skipped.

## Pattern / gotcha

When adding a **conditionally-skipped** job to another job's `needs`, you MUST switch that
job's `if:` to `!cancelled()` (or `always()`) + explicit `needs.<x>.result` checks, or the
skip cascades and silently disables the downstream job. Allow `== 'skipped'` for the
intentionally-skipped dependency; block only `failure`/`cancelled`.
