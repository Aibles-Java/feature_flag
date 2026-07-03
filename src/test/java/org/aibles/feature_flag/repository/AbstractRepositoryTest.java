package org.aibles.feature_flag.repository;

import jakarta.persistence.EntityManager;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.OrganizationMember;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.domain.enums.FlagValueType;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.util.ApiKeyGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
abstract class AbstractRepositoryTest {

    @Autowired
    protected EntityManager em;

    protected <T> T save(T entity) {
        em.persist(entity);
        em.flush();
        return entity;
    }

    protected User persistUser(String email) {
        return save(User.builder()
                .email(email)
                .passwordHash("hashed")
                .firstName("First")
                .lastName("Last")
                .build());
    }

    protected Organization persistOrg(String slug) {
        return save(Organization.builder()
                .name("Org " + slug)
                .slug(slug)
                .build());
    }

    protected Project persistProject(Organization org, String name) {
        return save(Project.builder()
                .organization(org)
                .name(name)
                .build());
    }

    protected Environment persistEnv(Project project, String name) {
        return save(Environment.builder()
                .project(project)
                .name(name)
                .apiKey(ApiKeyGenerator.generate())
                .build());
    }

    protected FeatureFlag persistFlag(Project project, String key, boolean archived) {
        return save(FeatureFlag.builder()
                .project(project)
                .name(key)
                .key(key)
                .valueType(FlagValueType.BOOLEAN)
                .archived(archived)
                .build());
    }

    protected FlagEnvironmentState persistState(FeatureFlag flag, Environment env, boolean enabled) {
        return save(FlagEnvironmentState.builder()
                .featureFlag(flag)
                .environment(env)
                .enabled(enabled)
                .build());
    }

    protected OrganizationMember persistMember(Organization org, User user, MemberRole role) {
        return save(OrganizationMember.builder()
                .organization(org)
                .user(user)
                .role(role)
                .build());
    }
}
