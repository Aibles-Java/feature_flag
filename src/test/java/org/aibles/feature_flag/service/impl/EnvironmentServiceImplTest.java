package org.aibles.feature_flag.service.impl;

import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.Organization;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.EnvType;
import org.aibles.feature_flag.dto.request.UpdateEnvironmentRequest;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards on environment updates: changing protection attributes (type / production change
 * window) requires the OWNER-only ENV_MANAGE_PROTECTION action, so an ADMIN cannot strip
 * production protection to bypass rules B/D.
 */
@ExtendWith(MockitoExtension.class)
class EnvironmentServiceImplTest {

    @Mock private EnvironmentRepository environmentRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private PermissionService permissionService;

    private EnvironmentServiceImpl service;

    private final UUID envId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new EnvironmentServiceImpl(environmentRepository, projectRepository, permissionService);
        Project project = Project.builder().id(projectId)
                .organization(Organization.builder().id(UUID.randomUUID()).build()).build();
        Environment env = Environment.builder()
                .id(envId).project(project).type(EnvType.PRODUCTION).build();
        when(environmentRepository.findById(envId)).thenReturn(Optional.of(env));
    }

    @Test
    void downgradingProductionTypeRequiresManageProtection() {
        // ADMIN holds ENV_UPDATE but not the protection action → cannot downgrade PROD→DEV.
        doNothing().when(permissionService).check(eq(Action.ENV_UPDATE), any());
        doThrow(new UnauthorizedException("nope"))
                .when(permissionService).check(eq(Action.ENV_MANAGE_PROTECTION), any());

        UpdateEnvironmentRequest req = new UpdateEnvironmentRequest();
        req.setType(EnvType.DEVELOPMENT);

        assertThatThrownBy(() -> service.update(envId, req)).isInstanceOf(UnauthorizedException.class);
        verify(environmentRepository, never()).save(any());
    }

    @Test
    void editingChangeWindowRequiresManageProtection() {
        doNothing().when(permissionService).check(eq(Action.ENV_UPDATE), any());
        doThrow(new UnauthorizedException("nope"))
                .when(permissionService).check(eq(Action.ENV_MANAGE_PROTECTION), any());

        UpdateEnvironmentRequest req = new UpdateEnvironmentRequest();
        req.setChangeWindowStartHour(0);
        req.setChangeWindowEndHour(0);

        assertThatThrownBy(() -> service.update(envId, req)).isInstanceOf(UnauthorizedException.class);
        verify(environmentRepository, never()).save(any());
    }

    @Test
    void nonProtectionUpdateDoesNotRequireManageProtection() {
        // Only description changes → ENV_MANAGE_PROTECTION must NOT be checked.
        when(environmentRepository.save(any(Environment.class))).thenAnswer(inv -> inv.getArgument(0));
        UpdateEnvironmentRequest req = new UpdateEnvironmentRequest();
        req.setDescription("just a description");

        assertThatCode(() -> service.update(envId, req)).doesNotThrowAnyException();
        verify(permissionService, never()).check(eq(Action.ENV_MANAGE_PROTECTION), any());
    }
}
