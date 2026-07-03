package org.aibles.feature_flag.repository;

import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizationMemberRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private OrganizationMemberRepository repository;

    @Test
    void findOrganizationIdsByUserIdReturnsEveryOrgTheUserBelongsTo() {
        Organization orgA = persistOrg("acme");
        Organization orgB = persistOrg("globex");
        Organization orgC = persistOrg("initech");
        User user = persistUser("member@example.com");
        persistMember(orgA, user, MemberRole.OWNER);
        persistMember(orgB, user, MemberRole.VIEWER);

        assertThat(repository.findOrganizationIdsByUserId(user.getId()))
                .containsExactlyInAnyOrder(orgA.getId(), orgB.getId());
        assertThat(repository.findOrganizationIdsByUserId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void countByOrganizationIdAndRoleCountsOnlyMatchingRole() {
        Organization org = persistOrg("acme");
        persistMember(org, persistUser("owner1@example.com"), MemberRole.OWNER);
        persistMember(org, persistUser("owner2@example.com"), MemberRole.OWNER);
        persistMember(org, persistUser("viewer@example.com"), MemberRole.VIEWER);

        assertThat(repository.countByOrganizationIdAndRole(org.getId(), MemberRole.OWNER)).isEqualTo(2);
        assertThat(repository.countByOrganizationIdAndRole(org.getId(), MemberRole.VIEWER)).isEqualTo(1);
        assertThat(repository.countByOrganizationIdAndRole(org.getId(), MemberRole.ADMIN)).isZero();
    }

    @Test
    void membershipLookupsResolveByOrgAndUser() {
        Organization org = persistOrg("acme");
        User user = persistUser("member@example.com");
        persistMember(org, user, MemberRole.ADMIN);

        assertThat(repository.existsByOrganizationIdAndUserId(org.getId(), user.getId())).isTrue();
        assertThat(repository.existsByOrganizationIdAndUserId(org.getId(), UUID.randomUUID())).isFalse();
        assertThat(repository.findByOrganizationIdAndUserId(org.getId(), user.getId()))
                .isPresent()
                .get()
                .extracting(m -> m.getRole())
                .isEqualTo(MemberRole.ADMIN);
    }

    @Test
    void findAllByOrganizationIdReturnsEveryMember() {
        Organization org = persistOrg("acme");
        Organization other = persistOrg("globex");
        persistMember(org, persistUser("a@example.com"), MemberRole.OWNER);
        persistMember(org, persistUser("b@example.com"), MemberRole.VIEWER);
        persistMember(other, persistUser("c@example.com"), MemberRole.OWNER);

        assertThat(repository.findAllByOrganizationId(org.getId())).hasSize(2);
    }
}
