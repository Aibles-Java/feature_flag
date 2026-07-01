<!-- Keep the title in Conventional Commits format: type(scope): subject -->

## Summary

<!-- What changed and why. 1-3 sentences, focus on the "why". -->

## Related issue/ticket

<!-- Link the Jira/Linear/GitHub issue, or write "N/A" -->

## Changes

<!-- Bullet list of the key changes, for the reviewer -->
-

## Test plan

<!-- How was this verified? Check what applies, add commands/output if useful. -->
- [ ] `./mvnw test` passes
- [ ] Manually verified via Swagger UI / SDK call
- [ ] New/updated tests added for the change

## Screenshots / evidence

<!-- Swagger UI, curl output, logs, etc. Delete this section if not applicable. -->

## Reviewer checklist

- [ ] No breaking changes to `FeatureFlag.key` immutability or SDK response shape
- [ ] If `security/`, JWT config, or `ApiKeyGenerator` touched: security review done
- [ ] If `db/changelog/migrations/` touched: new changeset only, no edits to already-run changesets
- [ ] Base branch is correct (`develop` for features, `main` for hotfixes/releases)
