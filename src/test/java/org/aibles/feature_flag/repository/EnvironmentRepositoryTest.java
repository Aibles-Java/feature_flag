package org.aibles.feature_flag.repository;

import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private EnvironmentRepository repository;

    @Test
    void findAllByProjectIdReturnsOnlyThatProjectsEnvironments() {
        Organization org = persistOrg("acme");
        Project a = persistProject(org, "A");
        Project b = persistProject(org, "B");
        persistEnv(a, "production");
        persistEnv(a, "staging");
        persistEnv(b, "production");

        assertThat(repository.findAllByProjectId(a.getId())).hasSize(2);
        assertThat(repository.findAllByProjectId(b.getId())).hasSize(1);
    }

    @Test
    void findByApiKeyResolvesTheOwningEnvironment() {
        Organization org = persistOrg("acme");
        Project project = persistProject(org, "Billing");
        Environment env = persistEnv(project, "production");

        assertThat(repository.findByApiKey(env.getApiKey()))
                .isPresent()
                .get()
                .extracting(Environment::getId)
                .isEqualTo(env.getId());
        assertThat(repository.findByApiKey("no-such-key")).isEmpty();
    }

    @Test
    void existsByProjectIdAndNameIsScopedToProject() {
        Organization org = persistOrg("acme");
        Project a = persistProject(org, "A");
        Project b = persistProject(org, "B");
        persistEnv(a, "production");

        assertThat(repository.existsByProjectIdAndName(a.getId(), "production")).isTrue();

        assertThat(repository.existsByProjectIdAndName(b.getId(), "production")).isFalse();
    }
}
