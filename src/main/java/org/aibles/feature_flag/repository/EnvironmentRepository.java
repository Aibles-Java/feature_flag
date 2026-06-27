package org.aibles.feature_flag.repository;

import org.aibles.feature_flag.domain.entity.Environment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {
    List<Environment> findAllByProjectId(UUID projectId);
    Optional<Environment> findByApiKey(String apiKey);
    boolean existsByProjectIdAndName(UUID projectId, String name);
}
