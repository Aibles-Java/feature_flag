# Feature Flag Client SDK

A pure Java 21 client SDK for the Feature Flag platform. Zero Spring runtime dependencies — works in any JVM application (Spring Boot, Quarkus, plain Java, etc.).

## Maven coordinates

```xml
<dependency>
  <groupId>org.aibles</groupId>
  <artifactId>feature-flag-sdk</artifactId>
  <version>0.0.1-SNAPSHOT</version>
</dependency>
```

## Quick start

### Spring Boot singleton bean

```java
@Configuration
public class FeatureFlagConfig {

  @Value("${feature-flag.server-url}")          // https://flags.internal
  private String serverUrl;

  @Value("${feature-flag.api-key}")             // inject from secrets manager — NEVER hardcode
  private String apiKey;

  @Bean
  @Lazy   // avoids startup failure when the flag server is not yet reachable
  public FlagClient flagClient() throws SdkConfigurationException {
    return new FlagClientBuilder()
        .serverUrl(serverUrl)
        .apiKey(apiKey)          // load from AWS Secrets Manager / Vault / env var at runtime
        .cacheTtlSeconds(60)
        .maxStaleSeconds(300)    // serve stale for up to 5 min on server outage
        .connectTimeoutMs(3_000)
        .readTimeoutMs(5_000)
        .onAuthFailure(ex -> log.error("Feature flag API key rejected — rotate immediately"))
        .build();
  }
}
```

Usage in a service:

```java
@Service
@RequiredArgsConstructor
public class CheckoutService {

  private final FlagClient flagClient;

  public void processOrder(String userId, Order order) {
    boolean newFlowEnabled = flagClient.getBooleanValue("checkout-v2", userId, false);
    if (newFlowEnabled) {
      newCheckoutFlow(order);
    } else {
      legacyCheckoutFlow(order);
    }
  }
}
```

### Plain Java example

```java
public class Main {
  public static void main(String[] args) throws Exception {
    try (FlagClient client = new FlagClientBuilder()
        .serverUrl("https://flags.internal")
        .apiKey(System.getenv("FEATURE_FLAG_API_KEY"))  // from environment — NEVER hardcode
        .cacheTtlSeconds(30)
        .build()) {

      boolean enabled = client.getBooleanValue("my-feature", false);
      System.out.println("my-feature enabled: " + enabled);
    }
  }
}
```

## Configuration options

| Option | Builder method | Default | Description |
|---|---|---|---|
| Server URL | `serverUrl(String)` | required | HTTPS base URL of the flag server. `http://` URLs are rejected at build time. |
| API Key | `apiKey(String)` | required | Environment API key. Load from a secrets manager — never hardcode. |
| Cache TTL | `cacheTtlSeconds(int)` | 60 | Seconds a cached flag value is considered fresh. Range: 1–3600. |
| Max stale age | `maxStaleSeconds(int)` | 0 (unlimited) | Maximum age (seconds) of a stale entry that may be served when the server is unreachable. 0 = serve indefinitely. |
| Connect timeout | `connectTimeoutMs(int)` | 5000 | HTTP connect timeout in milliseconds. |
| Read timeout | `readTimeoutMs(int)` | 10000 | HTTP response read timeout in milliseconds. |
| Auth-failure hook | `onAuthFailure(Consumer<InvalidApiKeyException>)` | no-op | Callback invoked synchronously when the server returns HTTP 401. Use for alerting or key-refresh logic. |

## Evaluation API

All `get*Value` methods have two overloads: with and without an `identifier`.

```java
// Without identifier (rollout uses server default: fully on)
boolean v1 = client.getBooleanValue("flag-key", defaultValue);
String  v2 = client.getStringValue("flag-key", defaultValue);
int     v3 = client.getIntValue("flag-key", defaultValue);
MyDto   v4 = client.getJsonValue("flag-key", MyDto.class, defaultValue);

// With identifier (rollout bucketing: same identifier always gives the same result)
boolean v1 = client.getBooleanValue("flag-key", "user-id-123", defaultValue);
String  v2 = client.getStringValue("flag-key", "user-id-123", defaultValue);
int     v3 = client.getIntValue("flag-key", "user-id-123", defaultValue);
MyDto   v4 = client.getJsonValue("flag-key", "user-id-123", MyDto.class, defaultValue);
```

## Graceful degradation contract

The SDK is designed to never break the calling application on transient server outages:

1. **Cache-first**: A fresh (within TTL) cached value is returned without any HTTP call.
2. **Serve-stale**: When the server is unreachable after all retries, a previously cached (expired) value is returned if available, up to `maxStaleSeconds`.
3. **Caller default**: If no cached value exists and the server is unreachable, the `defaultValue` argument supplied by the caller is returned.
4. **Exception only on 401**: `InvalidApiKeyException` is thrown when the server returns HTTP 401. All other failures degrade silently to the caller default.

Retry policy: 100 ms initial delay, x2 exponential backoff, 3 max retries, 20% jitter. Status codes 401/403/404 are never retried.

## Rollout flags

A flag is on a partial rollout when `rolloutPercent` is between 1 and 99 (inclusive). For these flags:

- **Rollout flags bypass the in-memory cache** on every call (ADR-SDK-004): each evaluation goes to the server so the identifier-based bucketing is always fresh.
- **Identifier is required for deterministic bucketing**: the same identifier always maps to the same outcome for a given flag. The bucketing is performed server-side.
- **No identifier = fully on** (server behaviour): when identifier is omitted the server returns the flag as fully enabled. This is an intentional fail-open design; rollout percentage is not an access-control gate.

### Confidential rollout fail-closed (ADR-SDK-004 §E2)

For flag keys that contain any of: `card`, `payment`, `fraud`, `kyc`, `aml`, `security`, `pci` (case-insensitive), the SDK applies a stricter rule: if such a flag is on a partial rollout **and no identifier is supplied**, the SDK returns the caller's `defaultValue` instead of the server's fully-on value. Provide an identifier to evaluate these flags normally.

Example:

```java
// Fails closed (returns false) — "payment-v2" matches pattern, no identifier supplied
boolean safe = client.getBooleanValue("payment-v2", false);

// Evaluates normally — identifier supplied
boolean normal = client.getBooleanValue("payment-v2", userId, false);
```

## Diagnostics

```java
DiagnosticsSnapshot snap = client.diagnostics();
System.out.printf("Hit ratio: %.1f%%, server errors: %d%n",
    snap.hitRatio() * 100, snap.serverErrors());
```

Fields: `cacheHits`, `cacheMisses`, `serverErrors`, `invalidKeyEvents`, `hitRatio()`.

## Thread safety

`FlagClient` is fully thread-safe and designed to be used as a singleton. All internal state (cache, counters) is protected by lock-free concurrent data structures.

## Lifecycle

`FlagClient` implements `AutoCloseable`. Call `close()` to release the background cache-eviction thread:

```java
try (FlagClient client = new FlagClientBuilder()...build()) {
    // use client
}
```

`close()` is idempotent — safe to call multiple times.

## Known deviations from design / planned v2 updates

### Query-param transport for `identifier`

The current SDK sends the `identifier` as a **query parameter** (`?identifier=...`) rather than as the `X-Flag-Identifier` header originally described in the LLD design. This matches the **live server contract**: `EvaluationController` reads identifier via `@RequestParam(required = false) String identifier`.

Moving identifier to an `X-Flag-Identifier` header is deferred to a later phase and requires a coordinated server-side change (`EvaluationController` must read the header instead of / in addition to the query param). Until that coordinated change ships, using the header would silently break rollout evaluation in production.

### Residual DE-07: `identifier` visible in server/proxy/LB access logs

Because `identifier` is transmitted as a query parameter, it **will appear in the flag server's access logs, any reverse-proxy access logs, and load-balancer logs** (DE-07 residual risk). Platform/server owners should:

- Ensure access-log scrubbing rules mask the `identifier=` query parameter value before logs are shipped to a SIEM or long-term storage.
- Consider access-control on the flag server's access logs (limit to ops team) until the header-transport migration ships.
- Treat any flag-server log containing `identifier=<userId>` as potentially PII under the data classification policy.

This residual is tracked and will be resolved when the `identifier` transport is migrated to the `X-Flag-Identifier` header in a future coordinated release.

## Security notes

- The API key is sent as `X-Environment-Key` header. It is **never** logged, serialized to JSON, or included in `toString()` output.
- The SDK enforces TLS 1.2+ (TLS 1.1 and below are rejected). Only the JVM's default trust store is used — there is no trust-all override.
- `serverUrl` must be an `https://` URL. Plain `http://` URLs throw `SdkConfigurationException` at build time.
- JSON flag values are deserialized with Jackson. Default typing is never enabled (ADR-SDK-003 polymorphic-deserialization guard). Values larger than 256 KB are rejected.
