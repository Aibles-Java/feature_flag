package org.aibles.feature_flag.repository;

import org.aibles.feature_flag.domain.entity.Organization;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizationRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private OrganizationRepository repository;

    @Test
    void findBySlugResolvesOrganisation() {
        persistOrg("acme");

        assertThat(repository.findBySlug("acme"))
                .isPresent()
                .get()
                .extracting(Organization::getSlug)
                .isEqualTo("acme");
        assertThat(repository.findBySlug("missing")).isEmpty();
    }

    @Test
    void existsBySlugReflectsPresence() {
        persistOrg("acme");

        assertThat(repository.existsBySlug("acme")).isTrue();
        assertThat(repository.existsBySlug("globex")).isFalse();
    }
}
