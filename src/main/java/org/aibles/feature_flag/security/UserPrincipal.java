package org.aibles.feature_flag.security;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import lombok.Getter;
import org.aibles.feature_flag.domain.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Getter
public class UserPrincipal implements UserDetails {

  private final UUID id;
  private final String email;
  private final String password;

  private UserPrincipal(UUID id, String email, String password) {
    this.id = id;
    this.email = email;
    this.password = password;
  }

  public static UserPrincipal from(User user) {
    return new UserPrincipal(user.getId(), user.getEmail(), user.getPasswordHash());
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return Collections.emptyList();
  }

  @Override
  public String getUsername() {
    return email;
  }

  @Override
  public boolean isEnabled() {
    return true;
  }
}
