package org.aibles.feature_flag.controller.admin;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.enums.HygieneStatus;
import org.aibles.feature_flag.dto.response.FlagHygieneResponse;
import org.aibles.feature_flag.dto.response.PageResponse;
import org.aibles.feature_flag.service.FlagHygieneService;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/flag-hygiene")
@RequiredArgsConstructor
public class FlagHygieneController {

  private final FlagHygieneService flagHygieneService;

  /**
   * Flag debt for a project — one row per active (flag, environment) pair.
   *
   * <p>Sorted oldest-evaluation-first by default, so the flags most likely to be removable are on
   * page one. {@code lastEvaluatedAt} is nullable and never-evaluated flags are the strongest
   * signal, so the sort is ascending with the state id as a deterministic tiebreak.
   */
  @GetMapping
  public PageResponse<FlagHygieneResponse> report(
      @RequestParam UUID projectId,
      @RequestParam(defaultValue = "ALL") HygieneStatus status,
      @ParameterObject
          @PageableDefault(
              size = 20,
              sort = {"lastEvaluatedAt", "id"},
              direction = Sort.Direction.ASC)
          Pageable pageable) {
    return PageResponse.from(flagHygieneService.report(projectId, status, pageable));
  }
}
