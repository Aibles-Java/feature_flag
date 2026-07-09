package org.aibles.feature_flag.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Central façade for the platform's custom business metrics (issue #29).
 *
 * <p>All meters are registered lazily through {@link MeterRegistry}, which caches by
 * name + tag set, so calling these methods on the hot path is cheap after the first hit.
 *
 * <p><strong>Tag cardinality is deliberately bounded.</strong> The only unbounded-looking
 * tag is {@code environment}, which carries the environment <em>id</em> (UUID) — bounded by
 * the number of environments a tenant creates, never a free-form string. Auth-failure and
 * flag-change reasons are drawn from small fixed enums below.
 */
@Component
public class FeatureFlagMetrics {

    // --- meter names (Micrometer dot-form → Prometheus snake_case + _total/_seconds suffixes) ---
    static final String EVALUATIONS = "ff.evaluations";                 // → ff_evaluations_total
    static final String EVALUATION_DURATION = "ff.evaluation.duration"; // → ff_evaluation_duration_seconds
    static final String FLAG_CHANGES = "ff.flag.changes";               // → ff_flag_changes_total
    static final String AUTH_FAILURES = "ff.auth.failures";             // → ff_auth_failures_total

    /** Bounded values for the {@code change} tag on {@link #FLAG_CHANGES}. */
    public enum FlagChange {
        CREATED, UPDATED, ARCHIVED, UNARCHIVED, STATE_UPDATED;

        private String tag() {
            return name().toLowerCase();
        }
    }

    /** Bounded values for the {@code chain}/{@code reason} tags on {@link #AUTH_FAILURES}. */
    public enum AuthFailure {
        SDK_MISSING_KEY("sdk", "missing_key"),
        SDK_INVALID_KEY("sdk", "invalid_key"),
        ADMIN_INVALID_TOKEN("admin", "invalid_token"),
        ADMIN_UNKNOWN_SUBJECT("admin", "unknown_subject");

        private final String chain;
        private final String reason;

        AuthFailure(String chain, String reason) {
            this.chain = chain;
            this.reason = reason;
        }
    }

    private final MeterRegistry registry;

    public FeatureFlagMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Eagerly register the bounded meters at zero so they always appear in a scrape — Prometheus
        // rate() over a counter that only springs into existence on first event is otherwise blind to
        // the 0→1 edge. (Per-environment evaluation meters can't be pre-registered; the env id is
        // unknown until traffic arrives.)
        for (FlagChange change : FlagChange.values()) {
            registry.counter(FLAG_CHANGES, "change", change.tag());
        }
        for (AuthFailure failure : AuthFailure.values()) {
            registry.counter(AUTH_FAILURES, "chain", failure.chain, "reason", failure.reason);
        }
    }

    /**
     * Times an SDK evaluation for {@code environmentId} and counts it. The timer emits
     * {@code ff_evaluation_duration_seconds_{count,sum,max}}; the separate counter satisfies
     * the explicit {@code ff_evaluations_total} metric in the issue's acceptance criteria.
     */
    public <T> T recordEvaluation(String environmentId, Supplier<T> evaluation) {
        registry.counter(EVALUATIONS, "environment", environmentId).increment();
        return Timer.builder(EVALUATION_DURATION)
                .description("SDK flag evaluation latency")
                .tag("environment", environmentId)
                .register(registry)
                .record(evaluation);
    }

    /** Increments {@code ff_flag_changes_total{change=...}} for an admin mutation. */
    public void recordFlagChange(FlagChange change) {
        registry.counter(FLAG_CHANGES, "change", change.tag()).increment();
    }

    /** Increments {@code ff_auth_failures_total{chain=...,reason=...}}. */
    public void recordAuthFailure(AuthFailure failure) {
        registry.counter(AUTH_FAILURES, "chain", failure.chain, "reason", failure.reason).increment();
    }
}
