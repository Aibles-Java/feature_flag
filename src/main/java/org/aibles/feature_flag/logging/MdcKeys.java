package org.aibles.feature_flag.logging;

/**
 * SLF4J {@link org.slf4j.MDC} keys used for per-request log correlation (issue #28).
 *
 * <p>{@link #REQUEST_ID} is populated for every request by {@link RequestCorrelationFilter}, which
 * runs ahead of all security chains. {@link #USER_ID} / {@link #ENV_ID} are added by the respective
 * authentication filters once (and only if) the caller is authenticated. All three are cleared in a
 * single place — the correlation filter's {@code finally} — so nothing leaks onto a pooled thread's
 * next request.
 */
public final class MdcKeys {

  public static final String REQUEST_ID = "requestId";
  public static final String USER_ID = "userId";
  public static final String ENV_ID = "envId";

  private MdcKeys() {}
}
