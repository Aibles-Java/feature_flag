package org.aibles.feature_flag.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.CustomRole;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomRoleRepository extends JpaRepository<CustomRole, UUID> {

  List<CustomRole> findAllByOrganizationId(UUID organizationId);

  Optional<CustomRole> findByOrganizationIdAndName(UUID organizationId, String name);

  boolean existsByOrganizationIdAndName(UUID organizationId, String name);
}
