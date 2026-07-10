package org.aibles.feature_flag.service;

import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.dto.request.CreateEnvironmentRequest;
import org.aibles.feature_flag.dto.request.UpdateEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;

public interface EnvironmentService {
  /** Returns the plaintext API key exactly once — see {@link EnvironmentSecretResponse}. */
  EnvironmentSecretResponse create(CreateEnvironmentRequest request);

  List<EnvironmentResponse> listByProject(UUID projectId);

  EnvironmentResponse get(UUID id);

  EnvironmentResponse update(UUID id, UpdateEnvironmentRequest request);

  void delete(UUID id);

  /** Returns the new plaintext API key exactly once — see {@link EnvironmentSecretResponse}. */
  EnvironmentSecretResponse rotateApiKey(UUID id);
}
