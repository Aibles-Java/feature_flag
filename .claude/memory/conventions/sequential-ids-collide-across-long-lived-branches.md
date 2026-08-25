---
name: sequential-ids-collide-across-long-lived-branches
description: Liquibase changeset numbers and ADR numbers are picked sequentially, so two unmerged branches silently claim the same one — check every unmerged branch, not just develop, before choosing the next id
metadata:
  type: convention
---

# Sequential ids collide across long-lived branches

Both `db/changelog/migrations/NNN-*.xml` and `docs/adr/ADR-NNNN-*.md` are numbered by "look at the
highest one and add 1". That is correct on a single branch and wrong the moment two feature branches
are open at once — each picks the same next number, and the collision only surfaces at merge time.

Real instance: `feature/role` (branched 2026-07-01) numbered its five ABAC changesets `009`–`013`.
By the time it was merged, `develop` had `009-hash-api-keys`, `010-create-refresh-tokens`,
`011-create-audit-log` — a three-way clash. Renumbering to `013`–`017` also required editing the
`changeSet id` **inside** each file (it is `id="009-add-environment-type"`, matching the filename)
and the includes in `db.changelog-master.xml`. Same story for ADRs: `ADR-0005` is already claimed by
the unmerged `feature/issue-36-webhooks`, so the ABAC record had to take `0006`.

**Before picking the next number, check every unmerged branch, not just `develop`:**

```bash
for b in $(git branch -r --no-merged origin/develop --format='%(refname:short)' | grep -v dependabot); do
  echo "== $b"; git ls-tree --name-only $b src/main/resources/db/changelog/migrations/ | sed 's|.*/||' | tail -3
done
```

Then skip the numbers other in-flight branches have taken (the ABAC merge left `012` free for the
webhooks branch on purpose) rather than taking the next free one on `develop`.

**Renumbering is safe only before the changeset has run anywhere real.** Liquibase keys
`DATABASECHANGELOG` on filename + id, so a rename makes an already-applied changeset look new and it
re-runs — failing with "column already exists". After renumbering, anyone who had run the old branch
locally must drop their dev DB (`docker compose down -v`) or hand-edit those rows. Never renumber a
changeset that reached staging or prod; add a corrective changeset instead.

**Not every enum change needs a migration.** `audit_log.action` / `.entity_type` are plain
`VARCHAR(32)` with no CHECK constraint, so adding `AuditAction` / `AuditEntityType` values is a
code-only change. `permission_grant.role` and `.scope_type` *do* carry CHECK constraints
(added via raw `<sql>` in `014`), so adding a value there does need a changeset. Read the migration
before assuming either way.
