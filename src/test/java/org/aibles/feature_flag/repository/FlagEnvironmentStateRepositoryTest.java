package org.aibles.feature_flag.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aibles.feature_flag.domain.entity.*;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb_jpa;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@Transactional
class FlagEnvironmentStateRepositoryTest {

    @PersistenceContext EntityManager em;
    @Autowired FlagEnvironmentStateRepository stateRepository;

    private <T> T persist(T entity) {
        em.persist(entity);
        return entity;
    }

    @Test
    void findAllActiveByEnvironmentId_excludesArchivedFlags() {
        TestFixtures fix = new TestFixtures();

        FeatureFlag active = fix.flag("active-flag", false);
        FeatureFlag archived = fix.flag("archived-flag", true);

        fix.state(active, fix.env);
        fix.state(archived, fix.env);
        em.flush();

        List<FlagEnvironmentState> result = stateRepository.findAllActiveByEnvironmentId(fix.env.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFeatureFlag().getKey()).isEqualTo("active-flag");
    }

    @Test
    void findAllActiveByEnvironmentId_returnsAllActiveFlags() {
        TestFixtures fix = new TestFixtures();

        fix.state(fix.flag("flag-a", false), fix.env);
        fix.state(fix.flag("flag-b", false), fix.env);
        fix.state(fix.flag("flag-c", true), fix.env);
        em.flush();

        List<FlagEnvironmentState> result = stateRepository.findAllActiveByEnvironmentId(fix.env.getId());

        assertThat(result).hasSize(2);
    }

    @Test
    void findAllActiveByEnvironmentId_returnsEmpty_whenAllFlagsArchived() {
        TestFixtures fix = new TestFixtures();

        fix.state(fix.flag("gone", true), fix.env);
        em.flush();

        List<FlagEnvironmentState> result = stateRepository.findAllActiveByEnvironmentId(fix.env.getId());

        assertThat(result).isEmpty();
    }

    @Test
    void findByFeatureFlagIdAndEnvironmentId_returnsState_whenExists() {
        TestFixtures fix = new TestFixtures();
        FeatureFlag flag = fix.flag("lookup-flag", false);
        fix.state(flag, fix.env);
        em.flush();

        Optional<FlagEnvironmentState> result =
                stateRepository.findByFeatureFlagIdAndEnvironmentId(flag.getId(), fix.env.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getFeatureFlag().getKey()).isEqualTo("lookup-flag");
    }

    class TestFixtures {
        final Organization org;
        final User user;
        final Project project;
        final Environment env;

        TestFixtures() {
            user = persist(User.builder()
                    .email("test-" + System.nanoTime() + "@example.com")
                    .passwordHash("hash").build());

            org = persist(Organization.builder()
                    .name("Org").slug("org-" + System.nanoTime()).build());

            persist(OrganizationMember.builder()
                    .organization(org).user(user).role(MemberRole.OWNER).build());

            project = persist(Project.builder()
                    .organization(org).name("Proj").build());

            env = persist(Environment.builder()
                    .project(project).name("env-" + System.nanoTime())
                    .apiKeyHash("key-" + System.nanoTime()).build());
        }

        FeatureFlag flag(String key, boolean archived) {
            return persist(FeatureFlag.builder()
                    .project(project).name(key).key(key)
                    .valueType(FlagValueType.BOOLEAN).archived(archived).build());
        }

        FlagEnvironmentState state(FeatureFlag flag, Environment environment) {
            return persist(FlagEnvironmentState.builder()
                    .featureFlag(flag).environment(environment).enabled(false).build());
        }
    }
}
