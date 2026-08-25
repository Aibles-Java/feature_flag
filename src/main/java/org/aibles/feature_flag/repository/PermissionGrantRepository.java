package org.aibles.feature_flag.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.PermissionGrant;
import org.aibles.feature_flag.domain.enums.ScopeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionGrantRepository extends JpaRepository<PermissionGrant, UUID> {

  Optional<PermissionGrant> findByUser_IdAndScopeTypeAndScopeId(
      UUID userId, ScopeType scopeType, UUID scopeId);

  List<PermissionGrant> findAllByScopeTypeAndScopeId(ScopeType scopeType, UUID scopeId);

  Page<PermissionGrant> findAllByScopeTypeAndScopeId(
      ScopeType scopeType, UUID scopeId, Pageable pageable);

  boolean existsByUser_IdAndScopeTypeAndScopeId(UUID userId, ScopeType scopeType, UUID scopeId);

  void deleteByUser_IdAndScopeTypeAndScopeIdIn(
      UUID userId, ScopeType scopeType, Collection<UUID> scopeIds);
}
