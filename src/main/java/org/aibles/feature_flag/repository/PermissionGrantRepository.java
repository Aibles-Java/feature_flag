package org.aibles.feature_flag.repository;

import org.aibles.feature_flag.domain.entity.PermissionGrant;
import org.aibles.feature_flag.domain.enums.ScopeType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionGrantRepository extends JpaRepository<PermissionGrant, UUID> {

    Optional<PermissionGrant> findByUser_IdAndScopeTypeAndScopeId(UUID userId, ScopeType scopeType, UUID scopeId);

    List<PermissionGrant> findAllByScopeTypeAndScopeId(ScopeType scopeType, UUID scopeId);

    boolean existsByUser_IdAndScopeTypeAndScopeId(UUID userId, ScopeType scopeType, UUID scopeId);

    void deleteByUser_IdAndScopeTypeAndScopeIdIn(UUID userId, ScopeType scopeType, Collection<UUID> scopeIds);
}
