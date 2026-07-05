package org.aibles.feature_flag.service.impl;

import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.util.ApiKeyHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EnvironmentServiceImpl} focused on the issue #24 invariant: create and
 * rotate return the plaintext key exactly once, but only its SHA-256 hash is ever persisted.
 */
@ExtendWith(MockitoExtension.class)
class EnvironmentServiceImplTest {

    @Mock
    private EnvironmentRepository environmentRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private PermissionService permissionService;

    @InjectMocks
    private EnvironmentServiceImpl service;

    @Test
    void createStoresHashAndReturnsPlaintextOnce() {
        UUID projectId = UUID.randomUUID();
        CreateEnvironmentRequest request = new CreateEnvironmentRequest();
        request.setProjectId(projectId);
        request.setName("prod");

        Project project = Project.builder().id(projectId).build();
        when(environmentRepository.existsByProjectIdAndName(projectId, "prod")).thenReturn(false);
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(environmentRepository.save(any(Environment.class))).thenAnswer(inv -> inv.getArgument(0));

        EnvironmentSecretResponse response = service.create(request);

        // The returned plaintext is a real 64-char hex key...
        assertThat(response.getApiKey()).matches("[0-9a-f]{64}");

        // ...and the persisted entity holds the hash of that plaintext, never the plaintext itself.
        ArgumentCaptor<Environment> saved = ArgumentCaptor.forClass(Environment.class);
        org.mockito.Mockito.verify(environmentRepository).save(saved.capture());
        assertThat(saved.getValue().getApiKeyHash())
                .isEqualTo(ApiKeyHasher.hash(response.getApiKey()))
                .isNotEqualTo(response.getApiKey());
    }

    @Test
    void rotateReplacesHashAndReturnsNewPlaintextOnce() {
        UUID envId = UUID.randomUUID();
        Environment env = Environment.builder()
                .id(envId)
                .project(Project.builder().id(UUID.randomUUID()).build())
                .apiKeyHash(ApiKeyHasher.hash("old-key"))
                .build();
        when(environmentRepository.findById(envId)).thenReturn(Optional.of(env));
        when(environmentRepository.save(any(Environment.class))).thenAnswer(inv -> inv.getArgument(0));

        EnvironmentSecretResponse response = service.rotateApiKey(envId);

        assertThat(response.getApiKey()).matches("[0-9a-f]{64}");
        assertThat(env.getApiKeyHash())
                .isEqualTo(ApiKeyHasher.hash(response.getApiKey()))
                .isNotEqualTo(ApiKeyHasher.hash("old-key")); // rotated away from the previous hash
    }
}
