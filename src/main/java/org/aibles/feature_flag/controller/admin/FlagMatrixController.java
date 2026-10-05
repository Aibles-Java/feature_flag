package org.aibles.feature_flag.controller.admin;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.config.PaginationConfig;
import org.aibles.feature_flag.dto.response.FlagMatrixRowResponse;
import org.aibles.feature_flag.dto.response.PageResponse;
import org.aibles.feature_flag.service.FlagMatrixService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Flag x environment matrix of one project, paginated by flag (S-2.5, ADR-02). */
@RestController
@RequestMapping("/api/v1/flags")
@RequiredArgsConstructor
public class FlagMatrixController {

  private final FlagMatrixService flagMatrixService;

  /**
   * Sort is deliberately not client-controllable (fixed createdAt,id order), so no client value can
   * reach the ORDER BY clause.
   */
  @GetMapping("/environment-states")
  public PageResponse<FlagMatrixRowResponse> matrix(
      @RequestParam UUID projectId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + PaginationConfig.MATRIX_DEFAULT_PAGE_SIZE) int size) {
    return flagMatrixService.getMatrix(projectId, page, size);
  }
}
