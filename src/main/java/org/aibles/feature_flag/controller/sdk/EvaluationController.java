package org.aibles.feature_flag.controller.sdk;

import io.swagger.v3.oas.annotations.Parameter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.dto.response.FlagEvaluationResponse;
import org.aibles.feature_flag.service.EvaluationService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sdk")
@RequiredArgsConstructor
public class EvaluationController {

  /**
   * Description of the optional {@code identifier} parameter, shared by both endpoints so the
   * fail-open contract is stated wherever an SDK author looks it up.
   */
  private static final String IDENTIFIER_DOC =
      """
      Stable caller identity (user id, device id, …) used to bucket this caller for flags on a \
      partial rollout. The same identifier always resolves to the same result for a given flag.

      Optional. When omitted, a flag on a partial rollout is returned as fully **on** — the \
      rollout is not applied, because there is nothing to bucket. A rollout percentage is \
      therefore not an access-control mechanism: any caller can obtain the "on" branch by \
      omitting this parameter. See docs/adr/ADR-0004-percentage-rollout-contract.md.""";

  private final EvaluationService evaluationService;

  @GetMapping("/flags")
  public List<FlagEvaluationResponse> getAllFlags(
      Authentication authentication,
      @Parameter(description = IDENTIFIER_DOC) @RequestParam(required = false) String identifier) {
    Environment env = (Environment) authentication.getPrincipal();
    return evaluationService.getAllFlags(env, identifier);
  }

  @GetMapping("/flags/{flagKey}")
  public FlagEvaluationResponse getFlag(
      Authentication authentication,
      @PathVariable String flagKey,
      @Parameter(description = IDENTIFIER_DOC) @RequestParam(required = false) String identifier) {
    Environment env = (Environment) authentication.getPrincipal();
    return evaluationService.getFlag(env, flagKey, identifier);
  }
}
