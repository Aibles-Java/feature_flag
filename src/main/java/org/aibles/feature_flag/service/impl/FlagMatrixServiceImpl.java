package org.aibles.feature_flag.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.config.PaginationConfig;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.dto.response.FeatureFlagResponse;
import org.aibles.feature_flag.dto.response.FlagMatrixRowResponse;
import org.aibles.feature_flag.dto.response.FlagStateResponse;
import org.aibles.feature_flag.dto.response.PageResponse;
import org.aibles.feature_flag.repository.FeatureFlagRepository;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.service.FlagMatrixService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flag x environment matrix (S-2.5). Authorization is at project scope ({@code FLAG_READ}) and is
 * the first thing that happens; every id used afterwards is derived from the authorized {@code
 * projectId}. Two statements regardless of flag/env count: the flag page (with the total in a
 * scalar subquery) and the states of that page. Never logs flag values (D-11, no read logging in
 * v1).
 */
@Service
@RequiredArgsConstructor
public class FlagMatrixServiceImpl implements FlagMatrixService {

  private final FeatureFlagRepository featureFlagRepository;
  private final FlagEnvironmentStateRepository flagStateRepository;
  private final PermissionService permissionService;

  @Override
  @Transactional(readOnly = true)
  public PageResponse<FlagMatrixRowResponse> getMatrix(UUID projectId, int page, int size) {
    permissionService.check(Action.FLAG_READ, PermissionService.ResourceRef.project(projectId));

    int safeSize =
        size < 1
            ? PaginationConfig.MATRIX_DEFAULT_PAGE_SIZE
            : Math.min(size, PaginationConfig.MAX_PAGE_SIZE);
    // keep page * size within int: the offset is narrowed to int downstream (else a huge page 500s)
    int safePage = Math.min(Math.max(page, 0), (Integer.MAX_VALUE / safeSize) - 1);

    List<Object[]> rows =
        featureFlagRepository.findActivePageWithTotal(
            projectId, PageRequest.of(safePage, safeSize));

    List<FeatureFlag> flags = new ArrayList<>(rows.size());
    long total;
    if (rows.isEmpty()) {
      // past the end (or no flags): the subquery had no row to ride on, so count explicitly
      total = safePage == 0 ? 0 : featureFlagRepository.countByProjectIdAndArchivedFalse(projectId);
    } else {
      total = ((Number) rows.get(0)[1]).longValue();
      for (Object[] r : rows) flags.add((FeatureFlag) r[0]);
    }

    Map<UUID, List<FlagStateResponse>> statesByFlag = new LinkedHashMap<>();
    if (!flags.isEmpty()) {
      List<UUID> ids = flags.stream().map(FeatureFlag::getId).toList();
      for (FlagEnvironmentState s : flagStateRepository.findMatrixStates(projectId, ids)) {
        statesByFlag
            .computeIfAbsent(s.getFeatureFlag().getId(), k -> new ArrayList<>())
            .add(toStateResponse(s));
      }
    }

    List<FlagMatrixRowResponse> content =
        flags.stream()
            .map(
                f ->
                    FlagMatrixRowResponse.builder()
                        .flag(toFlagResponse(f, projectId))
                        .states(statesByFlag.getOrDefault(f.getId(), List.of()))
                        .build())
            .toList();

    return PageResponse.<FlagMatrixRowResponse>builder()
        .content(content)
        .page(safePage)
        .size(safeSize)
        .totalElements(total)
        .totalPages((int) Math.ceil((double) total / safeSize))
        .build();
  }

  private static FeatureFlagResponse toFlagResponse(FeatureFlag flag, UUID projectId) {
    return FeatureFlagResponse.builder()
        .id(flag.getId())
        .name(flag.getName())
        .key(flag.getKey())
        .description(flag.getDescription())
        .valueType(flag.getValueType())
        .archived(flag.isArchived())
        .expiresAt(flag.getExpiresAt())
        .projectId(projectId)
        .createdAt(flag.getCreatedAt())
        .build();
  }

  private static FlagStateResponse toStateResponse(FlagEnvironmentState state) {
    return FlagStateResponse.builder()
        .flagId(state.getFeatureFlag().getId())
        .environmentId(state.getEnvironment().getId())
        .enabled(state.isEnabled())
        .value(state.getValue())
        .rolloutPercent(state.getRolloutPercent())
        .version(state.getVersion())
        .lastEvaluatedAt(state.getLastEvaluatedAt())
        .build();
  }
}
