package org.aibles.feature_flag.repository;

import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
  Optional<Organization> findBySlug(String slug);

  boolean existsBySlug(String slug);
}
