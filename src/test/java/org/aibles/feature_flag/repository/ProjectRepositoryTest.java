package org.aibles.feature_flag.repository;

import org.aibles.feature_flag.domain.entity.Organization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private ProjectRepository repository;

    @Test
    void findAllByOrganizationIdReturnsOnlyThatOrgsProjects() {
        Organization a = persistOrg("acme");
        Organization b = persistOrg("globex");
        persistProject(a, "Billing");
        persistProject(a, "Auth");
        persistProject(b, "Billing");

        assertThat(repository.findAllByOrganizationId(a.getId())).hasSize(2);
        assertThat(repository.findAllByOrganizationId(b.getId())).hasSize(1);
    }

    @Test
    void existsByOrganizationIdAndNameIsScopedToOrganisation() {
        Organization a = persistOrg("acme");
        Organization b = persistOrg("globex");
        persistProject(a, "Billing");

        assertThat(repository.existsByOrganizationIdAndName(a.getId(), "Billing")).isTrue();
        assertThat(repository.existsByOrganizationIdAndName(b.getId(), "Billing")).isFalse();
    }
}
