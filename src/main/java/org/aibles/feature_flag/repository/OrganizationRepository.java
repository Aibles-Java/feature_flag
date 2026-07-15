package org.aibles.feature_flag.repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Organization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
  Optional<Organization> findBySlug(String slug);

  boolean existsBySlug(String slug);

  /**
   * Paginated fetch of the organisations a user belongs to. The caller resolves the (small,
   * membership-bounded) id set first; sorting/paging then run against the {@code Organization}
   * root, so the default {@code createdAt,id} sort applies to the org's own columns.
   */
  Page<Organization> findByIdIn(Collection<UUID> ids, Pageable pageable);
}
