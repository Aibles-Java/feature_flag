package org.aibles.feature_flag.security;

import java.util.Collections;
import org.aibles.feature_flag.domain.entity.Environment;
import org.springframework.security.authentication.AbstractAuthenticationToken;

public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

  private final Environment environment;

  public ApiKeyAuthenticationToken(Environment environment) {
    super(Collections.emptyList());
    this.environment = environment;
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public Object getPrincipal() {
    return environment;
  }
}
