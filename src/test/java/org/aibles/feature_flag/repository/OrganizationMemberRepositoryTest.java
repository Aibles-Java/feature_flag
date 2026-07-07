package org.aibles.feature_flag.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.OrganizationMember;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_jpa;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@Transactional
class OrganizationMemberRepositoryTest {

    @PersistenceContext EntityManager em;
    @Autowired OrganizationMemberRepository memberRepository;

    User userA;
    User userB;
    Organization orgX;
    Organization orgY;

    private <T> T persist(T entity) {
        em.persist(entity);
        return entity;
    }

    @BeforeEach
    void setUp() {
        userA = persist(User.builder()
                .email("a-" + System.nanoTime() + "@example.com").passwordHash("h").build());
        userB = persist(User.builder()
                .email("b-" + System.nanoTime() + "@example.com").passwordHash("h").build());
        orgX = persist(Organization.builder()
                .name("OrgX").slug("orgx-" + System.nanoTime()).build());
        orgY = persist(Organization.builder()
                .name("OrgY").slug("orgy-" + System.nanoTime()).build());
    }

    private OrganizationMember member(User user, Organization org, MemberRole role) {
        return persist(OrganizationMember.builder()
                .user(user).organization(org).role(role).build());
    }

    @Test
    void findOrganizationIdsByUserId_returnsAllOrgsForUser() {
        member(userA, orgX, MemberRole.OWNER);
        member(userA, orgY, MemberRole.VIEWER);
        member(userB, orgX, MemberRole.ADMIN);
        em.flush();

        List<UUID> orgIds = memberRepository.findOrganizationIdsByUserId(userA.getId());

        assertThat(orgIds).containsExactlyInAnyOrder(orgX.getId(), orgY.getId());
    }

    @Test
    void findOrganizationIdsByUserId_returnsEmpty_whenUserHasNoMemberships() {
        em.flush();

        List<UUID> orgIds = memberRepository.findOrganizationIdsByUserId(userA.getId());

        assertThat(orgIds).isEmpty();
    }

    @Test
    void countByOrganizationIdAndRole_countsOnlyMatchingRole() {
        member(userA, orgX, MemberRole.OWNER);
        member(userB, orgX, MemberRole.OWNER);
        em.flush();

        long ownerCount = memberRepository.countByOrganizationIdAndRole(orgX.getId(), MemberRole.OWNER);
        long adminCount = memberRepository.countByOrganizationIdAndRole(orgX.getId(), MemberRole.ADMIN);

        assertThat(ownerCount).isEqualTo(2);
        assertThat(adminCount).isZero();
    }

    @Test
    void existsByOrganizationIdAndUserId_returnsTrue_whenMembershipExists() {
        member(userA, orgX, MemberRole.ADMIN);
        em.flush();

        boolean exists = memberRepository.existsByOrganizationIdAndUserId(orgX.getId(), userA.getId());

        assertThat(exists).isTrue();
    }

    @Test
    void existsByOrganizationIdAndUserId_returnsFalse_whenNotMember() {
        em.flush();

        boolean exists = memberRepository.existsByOrganizationIdAndUserId(orgX.getId(), userB.getId());

        assertThat(exists).isFalse();
    }

    @Test
    void findAllByOrganizationId_returnsAllMembers() {
        member(userA, orgX, MemberRole.OWNER);
        member(userB, orgX, MemberRole.VIEWER);
        member(userA, orgY, MemberRole.OWNER);
        em.flush();

        List<OrganizationMember> members = memberRepository.findAllByOrganizationId(orgX.getId());

        assertThat(members).hasSize(2);
    }
}
