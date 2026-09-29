package org.aibles.feature_flag.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.aibles.feature_flag.domain.entity.*;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:testdb_jpa;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=KEY,VALUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    })
@Transactional
class FeatureFlagRepositoryTest {

  @PersistenceContext EntityManager em;
  @Autowired FeatureFlagRepository flagRepository;

  Project project;

  private <T> T persist(T entity) {
    em.persist(entity);
    return entity;
  }

  @BeforeEach
  void setUp() {
    User user =
        persist(
            User.builder()
                .email("user-" + System.nanoTime() + "@example.com")
                .passwordHash("hash")
                .build());
    Organization org =
        persist(Organization.builder().name("Org").slug("org-" + System.nanoTime()).build());
    persist(
        OrganizationMember.builder().organization(org).user(user).role(MemberRole.OWNER).build());
    project = persist(Project.builder().organization(org).name("Proj").build());
  }

  private FeatureFlag persistFlag(String key, boolean archived) {
    return persist(
        FeatureFlag.builder()
            .project(project)
            .name(key)
            .key(key)
            .valueType(FlagValueType.BOOLEAN)
            .archived(archived)
            .build());
  }

  @Test
  void findAllByProjectIdAndArchivedFalse_excludesArchivedFlags() {
    persistFlag("active-a", false);
    persistFlag("active-b", false);
    persistFlag("archived-c", true);
    em.flush();

    List<FeatureFlag> result = flagRepository.findAllByProjectIdAndArchivedFalse(project.getId());

    assertThat(result).hasSize(2);
    assertThat(result)
        .extracting(FeatureFlag::getKey)
        .containsExactlyInAnyOrder("active-a", "active-b");
  }

  @Test
  void findAllByProjectIdAndArchivedTrue_returnsOnlyArchivedFlags() {
    persistFlag("live", false);
    persistFlag("old", true);
    em.flush();

    List<FeatureFlag> result = flagRepository.findAllByProjectIdAndArchivedTrue(project.getId());

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getKey()).isEqualTo("old");
  }

  @Test
  void findAllByProjectIdAndArchivedFalse_paginatesWithDeterministicSort() {
    for (int i = 0; i < 5; i++) {
      persistFlag("flag-" + i, false);
    }
    persistFlag("archived", true); // must be excluded
    em.flush();

    Sort sort = Sort.by("createdAt", "id");
    Page<FeatureFlag> firstPage =
        flagRepository.findAllByProjectIdAndArchivedFalse(
            project.getId(), PageRequest.of(0, 2, sort));

    // Envelope metadata reflects the 5 active flags split into pages of 2.
    assertThat(firstPage.getTotalElements()).isEqualTo(5);
    assertThat(firstPage.getTotalPages()).isEqualTo(3);
    assertThat(firstPage.getContent()).hasSize(2);

    // Walking every page yields each active key exactly once — proving a stable, gap-free order.
    List<String> collected = new ArrayList<>();
    for (int page = 0; page < firstPage.getTotalPages(); page++) {
      flagRepository
          .findAllByProjectIdAndArchivedFalse(project.getId(), PageRequest.of(page, 2, sort))
          .forEach(f -> collected.add(f.getKey()));
    }
    assertThat(collected)
        .doesNotHaveDuplicates()
        .containsExactlyInAnyOrder("flag-0", "flag-1", "flag-2", "flag-3", "flag-4");
  }

  @Test
  void findByProjectIdAndKey_returnsFlag_whenExists() {
    persistFlag("my-flag", false);
    em.flush();

    Optional<FeatureFlag> result = flagRepository.findByProjectIdAndKey(project.getId(), "my-flag");

    assertThat(result).isPresent();
    assertThat(result.get().getKey()).isEqualTo("my-flag");
  }

  @Test
  void findByProjectIdAndKey_returnsEmpty_whenKeyDoesNotExist() {
    em.flush();

    Optional<FeatureFlag> result =
        flagRepository.findByProjectIdAndKey(project.getId(), "no-such-key");

    assertThat(result).isEmpty();
  }

  @Test
  void existsByProjectIdAndKey_returnsTrue_whenFlagExists() {
    persistFlag("exists-flag", false);
    em.flush();

    boolean exists = flagRepository.existsByProjectIdAndKey(project.getId(), "exists-flag");

    assertThat(exists).isTrue();
  }

  @Test
  void existsByProjectIdAndKey_returnsFalse_whenFlagDoesNotExist() {
    em.flush();

    boolean exists = flagRepository.existsByProjectIdAndKey(project.getId(), "missing");

    assertThat(exists).isFalse();
  }
}
