package org.aibles.feature_flag.repository;

import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureFlagRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private FeatureFlagRepository repository;

    @Test
    void archivedAndActiveListingsArePartitioned() {
        Organization org = persistOrg("acme");
        Project project = persistProject(org, "Billing");
        persistFlag(project, "active-1", false);
        persistFlag(project, "active-2", false);
        persistFlag(project, "archived-1", true);

        assertThat(repository.findAllByProjectIdAndArchivedFalse(project.getId()))
                .extracting(f -> f.getKey())
                .containsExactlyInAnyOrder("active-1", "active-2");
        assertThat(repository.findAllByProjectIdAndArchivedTrue(project.getId()))
                .extracting(f -> f.getKey())
                .containsExactly("archived-1");
    }

    @Test
    void findByProjectIdAndKeyResolvesWithinProjectScope() {
        Organization org = persistOrg("acme");
        Project projectA = persistProject(org, "A");
        Project projectB = persistProject(org, "B");
        persistFlag(projectA, "shared-key", false);

        assertThat(repository.findByProjectIdAndKey(projectA.getId(), "shared-key")).isPresent();

        assertThat(repository.findByProjectIdAndKey(projectB.getId(), "shared-key")).isEmpty();
        assertThat(repository.findByProjectIdAndKey(projectA.getId(), "missing")).isEmpty();
    }

    @Test
    void keyColumnIsNotUpdatable() {

        Organization org = persistOrg("acme");
        Project project = persistProject(org, "Billing");
        FeatureFlag flag = persistFlag(project, "original-key", false);
        UUID id = flag.getId();

        flag.setName("renamed");
        flag.setKey("hacked-key");
        em.flush();
        em.clear();

        FeatureFlag reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("renamed");
        assertThat(reloaded.getKey()).isEqualTo("original-key");
    }

    @Test
    void existsByProjectIdAndKeyReflectsPresence() {
        Organization org = persistOrg("acme");
        Project project = persistProject(org, "Billing");
        persistFlag(project, "dark-mode", false);

        assertThat(repository.existsByProjectIdAndKey(project.getId(), "dark-mode")).isTrue();
        assertThat(repository.existsByProjectIdAndKey(project.getId(), "other")).isFalse();
        assertThat(repository.existsByProjectIdAndKey(UUID.randomUUID(), "dark-mode")).isFalse();
    }
}
