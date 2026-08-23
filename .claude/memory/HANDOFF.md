# Handoff

*Ephemeral — overwritten by `/save-memory` at the end of each session. Read this first.*

## Current WIP

Making CI green on branch **`fix/trivy-action-version`**.

- Earlier commits (`485c476`, `16b72a9`) pinned/bumped `aquasecurity/trivy-action` to a
  v-prefixed tag so the image-scan step actually runs.
- Once it ran (run 32654490354), the Trivy gate failed on a **real** HIGH:
  `org.postgresql:postgresql` 42.7.11 → CVE-2026-54291, fixed in 42.7.12.
- Fix committed as `94d4f5b`: override `<postgresql.version>42.7.12</postgresql.version>`
  in `pom.xml <properties>`. `./mvnw clean package` green on H2; `mvn help:evaluate`
  confirms the property resolves to 42.7.12.
- **Not yet pushed** — was blocked by the pre-push memory gate; this `/save-memory` run
  unblocks it.

## Context to Load

- [[0023-trivy-action-pin-and-postgres-cve-bump]] — the trivy-action pin + the CVE-bump
  pattern (override the Boot BOM property, never `.trivyignore` a fixed CVE).
- [[0018-docker-publish-trivy-gate-issue-34]] — the Trivy gate this fix keeps green.

## Next steps

1. Commit this memory + `git push` (memory gate now satisfied).
2. Watch the run on `fix/trivy-action-version`: the `publish` job's Trivy step should now be
   green (no HIGH/CRITICAL).
3. Open/refresh the PR for this branch → `develop` if not already open.
4. If any *new* HIGH/CRITICAL appears later, apply the same pattern: find the Boot-managed
   `<artifactId>.version` and bump it in `<properties>`.

## Known landmines

- **Windows docs case-collision** (`docs/ARCHITECTURE.md` vs `docs/architecture.md`): the
  phantom path is always dirty and `git merge` refuses to start. `develop` already renamed
  the uppercase file to `docs/architecture-design-v1.md`; this branch is off `develop` so it
  should be clean — do not re-touch `conventions/windows-docs-case-collision.md`.
- `./mvnw test -Dtest='A+B'` is not valid surefire syntax — use `-Dtest='A,B'`.
