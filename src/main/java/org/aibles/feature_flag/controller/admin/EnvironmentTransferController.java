package org.aibles.feature_flag.controller.admin;

import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.dto.request.CloneEnvironmentRequest;
import org.aibles.feature_flag.dto.request.ImportEnvironmentRequest;
import org.aibles.feature_flag.dto.response.EnvironmentSecretResponse;
import org.aibles.feature_flag.dto.response.EnvironmentSnapshotResponse;
import org.aibles.feature_flag.dto.response.ImportResultResponse;
import org.aibles.feature_flag.service.EnvironmentTransferService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Environment cloning and flag snapshot import/export (issue #38). Split from {@link
 * EnvironmentController} — same URL space, but these endpoints move flag configuration rather than
 * manage the environment record itself.
 */
@RestController
@RequestMapping("/api/v1/environments")
@RequiredArgsConstructor
public class EnvironmentTransferController {

  private final EnvironmentTransferService environmentTransferService;

  /** The response carries the clone's plaintext API key exactly once. */
  @PostMapping("/{envId}/clone")
  @ResponseStatus(HttpStatus.CREATED)
  public EnvironmentSecretResponse clone(
      @PathVariable UUID envId, @Valid @RequestBody CloneEnvironmentRequest request) {
    return environmentTransferService.clone(envId, request);
  }

  @GetMapping("/{envId}/export")
  public EnvironmentSnapshotResponse export(@PathVariable UUID envId) {
    return environmentTransferService.export(envId);
  }

  @PostMapping("/{envId}/import")
  public ImportResultResponse importSnapshot(
      @PathVariable UUID envId, @Valid @RequestBody ImportEnvironmentRequest request) {
    return environmentTransferService.importSnapshot(envId, request);
  }
}
