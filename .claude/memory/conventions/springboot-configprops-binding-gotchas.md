# Spring Boot @ConfigurationProperties validation gotchas (Boot 4.1)

Learned on issue #23 (`config/JwtProperties`). Three non-obvious behaviors:

1. **The binder passes unresolved `${VAR}` placeholders through as LITERALS.**
   `@Value("${...}")` throws on an unresolvable placeholder, but ConfigurationProperties
   binding (`PropertySourcesPlaceholdersResolver`) resolves non-strictly: a missing env
   var arrives as the literal string `${APP_JWT_SECRET}`. Any "required secret" property
   needs an explicit `secret.startsWith("${")` check to turn that into a clear error
   (see `JwtProperties.isSecretResolved`).

2. **`@AssertTrue` on records only fires on getter-shaped methods.** Hibernate Validator
   validates `isXxx()` boolean no-arg methods — NOT record accessors like `secret()`.
   Name cross-field/derived checks `isSomething()`. Property path in the report becomes
   `secretLongEnough` etc., not the field name.

3. **Value checks via `@AssertTrue` boolean getters keep secrets out of the failure
   report.** The bind-failure report prints `Property: ... Value: ...` per violation;
   for an `@AssertTrue` getter the rejected value is `false`, whereas a field-level
   constraint (`@Size` on `secret`) would echo the secret itself. Do NOT "simplify"
   these into field constraints (security-review confirmed this design).

Also: each `@AssertTrue` should return `true` when a *different* constraint owns the
case (null → `@NotBlank`; `${...}` literal → the resolved-check) to avoid double
reporting. Records auto-generate `toString()` with all components — override it to mask
secret-bearing components.

Related: [[0008-secrets-externalization-fail-fast]], [[springboot4-security-testing]]
