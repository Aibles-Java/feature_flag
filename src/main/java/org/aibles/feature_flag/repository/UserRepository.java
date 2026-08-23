package org.aibles.feature_flag.repository;

import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {
  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);
}
