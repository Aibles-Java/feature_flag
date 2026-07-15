package org.aibles.feature_flag.service;

import java.util.UUID;
import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.request.UpdateEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EnvironmentService {
  /** Returns the plaintext API key exactly once — see {@link EnvironmentSecretResponse}. */
  EnvironmentSecretResponse create(CreateEnvironmentRequest request);

  Page<EnvironmentResponse> listByProject(UUID projectId, Pageable pageable);

  EnvironmentResponse get(UUID id);

  EnvironmentResponse update(UUID id, UpdateEnvironmentRequest request);

  void delete(UUID id);

  /** Returns the new plaintext API key exactly once — see {@link EnvironmentSecretResponse}. */
  EnvironmentSecretResponse rotateApiKey(UUID id);
}
