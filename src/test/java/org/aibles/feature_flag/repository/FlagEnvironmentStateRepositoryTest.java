package org.aibles.feature_flag.repository;

import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FlagEnvironmentStateRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private FlagEnvironmentStateRepository repository;

    @Test
    void findAllActiveByEnvironmentIdExcludesArchivedFlags() {
        Organization org = persistOrg("acme");
        Project project = persistProject(org, "Billing");
        Environment env = persistEnv(project, "production");

        FeatureFlag active = persistFlag(project, "active-flag", false);
        FeatureFlag archived = persistFlag(project, "archived-flag", true);
        persistState(active, env, true);
        persistState(archived, env, true);

        List<FlagEnvironmentState> result = repository.findAllActiveByEnvironmentId(env.getId());

        assertThat(result)
                .extracting(s -> s.getFeatureFlag().getKey())
                .containsExactly("active-flag");
    }

    @Test
    void findAllActiveByEnvironmentIdIsScopedToTheGivenEnvironment() {
        Organization org = persistOrg("acme");
        Project project = persistProject(org, "Billing");
        Environment prod = persistEnv(project, "production");
        Environment staging = persistEnv(project, "staging");
        FeatureFlag flag = persistFlag(project, "flag", false);
        persistState(flag, prod, true);
        persistState(flag, staging, false);

        assertThat(repository.findAllActiveByEnvironmentId(prod.getId())).hasSize(1);
        assertThat(repository.findAllActiveByEnvironmentId(staging.getId())).hasSize(1);
        assertThat(repository.findAllActiveByEnvironmentId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findByFeatureFlagIdAndEnvironmentIdReturnsMatchingState() {
        Organization org = persistOrg("acme");
        Project project = persistProject(org, "Billing");
        Environment env = persistEnv(project, "production");
        FeatureFlag flag = persistFlag(project, "flag", false);
        persistState(flag, env, true);

        Optional<?> found = repository.findByFeatureFlagIdAndEnvironmentId(flag.getId(), env.getId());
        assertThat(found).isPresent();

        assertThat(repository.findByFeatureFlagIdAndEnvironmentId(flag.getId(), UUID.randomUUID()))
                .isEmpty();
    }

    @Test
    void findAllByFeatureFlagIdReturnsStateAcrossEnvironments() {
        Organization org = persistOrg("acme");
        Project project = persistProject(org, "Billing");
        Environment prod = persistEnv(project, "production");
        Environment staging = persistEnv(project, "staging");
        FeatureFlag flag = persistFlag(project, "flag", false);
        persistState(flag, prod, true);
        persistState(flag, staging, false);

        assertThat(repository.findAllByFeatureFlagId(flag.getId())).hasSize(2);
    }
}
