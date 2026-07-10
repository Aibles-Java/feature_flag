package org.aibles.feature_flag.repository;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
  List<Project> findAllByOrganizationId(UUID organizationId);

  boolean existsByOrganizationIdAndName(UUID organizationId, String name);
}
