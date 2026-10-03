package org.aibles.feature_flag.security;

import java.util.Collections;
import org.aibles.feature_flag.domain.entity.EnvironmentApiKey;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * SDK authentication token. The principal is the {@link EnvironmentApiKey} rather than the
 * environment, so downstream code can see which of an environment's keys made the call; the
 * environment is one hop away via {@link EnvironmentApiKey#getEnvironment()}.
 */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

  private final EnvironmentApiKey environmentApiKey;

  public ApiKeyAuthenticationToken(EnvironmentApiKey environmentApiKey) {
    super(Collections.emptyList());
    this.environmentApiKey = environmentApiKey;
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public Object getPrincipal() {
    return environmentApiKey;
  }
}
