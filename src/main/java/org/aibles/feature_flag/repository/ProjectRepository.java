package org.aibles.feature_flag.repository;

import java.util.UUID;
import org.aibles.feature_flag.domain.entity.Project;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
  Page<Project> findAllByOrganizationId(UUID organizationId, Pageable pageable);

  boolean existsByOrganizationIdAndName(UUID organizationId, String name);
}
