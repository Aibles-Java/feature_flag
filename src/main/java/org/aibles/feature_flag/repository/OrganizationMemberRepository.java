package org.aibles.feature_flag.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.aibles.feature_flag.domain.entity.OrganizationMember;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganizationMemberRepository extends JpaRepository<OrganizationMember, UUID> {
  List<OrganizationMember> findAllByOrganizationId(UUID organizationId);

  Optional<OrganizationMember> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

  boolean existsByOrganizationIdAndUserId(UUID organizationId, UUID userId);

  @Query("SELECT om.organization.id FROM OrganizationMember om WHERE om.user.id = :userId")
  List<UUID> findOrganizationIdsByUserId(@Param("userId") UUID userId);

  long countByOrganizationIdAndRole(UUID organizationId, MemberRole role);
}
