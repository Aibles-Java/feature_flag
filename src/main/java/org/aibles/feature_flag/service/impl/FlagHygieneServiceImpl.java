package org.aibles.feature_flag.service.impl;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.config.HygieneProperties;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.enums.HygieneStatus;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.response.FlagHygieneResponse;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.aibles.feature_flag.service.FlagHygieneService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FlagHygieneServiceImpl implements FlagHygieneService {

  private final FlagEnvironmentStateRepository flagStateRepository;
  private final HygieneProperties properties;
  private final PermissionService permissionService;

  @Override
  @Transactional(readOnly = true)
  public Page<FlagHygieneResponse> report(UUID projectId, HygieneStatus status, Pageable pageable) {
    permissionService.requireRoleForProject(
        projectId, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);

    // One "now" for the whole page: filtering and the per-row stale/expired flags must agree,
    // and re-reading the clock per row could classify two rows inconsistently.
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime staleBefore = now.minus(properties.staleAfter());

    Page<FlagEnvironmentState> rows =
        switch (status) {
          case STALE -> flagStateRepository.findStaleHygieneRows(projectId, staleBefore, pageable);
          case EXPIRED -> flagStateRepository.findExpiredHygieneRows(projectId, now, pageable);
          case ALL -> flagStateRepository.findHygieneRows(projectId, pageable);
        };

    return rows.map(state -> toResponse(state, now, staleBefore));
  }

  private FlagHygieneResponse toResponse(
      FlagEnvironmentState state, LocalDateTime now, LocalDateTime staleBefore) {
    FeatureFlag flag = state.getFeatureFlag();
    LocalDateTime lastEvaluatedAt = state.getLastEvaluatedAt();

    return FlagHygieneResponse.builder()
        .flagId(flag.getId())
        .flagKey(flag.getKey())
        .flagName(flag.getName())
        .environmentId(state.getEnvironment().getId())
        .environmentName(state.getEnvironment().getName())
        .enabled(state.isEnabled())
        .lastEvaluatedAt(lastEvaluatedAt)
        .daysSinceLastEvaluation(
            lastEvaluatedAt == null ? null : Duration.between(lastEvaluatedAt, now).toDays())
        .stale(isStale(flag, lastEvaluatedAt, staleBefore))
        .expiresAt(flag.getExpiresAt())
        .expired(isExpired(flag, now))
        .build();
  }

  /**
   * Mirrors the {@code findStaleHygieneRows} predicate exactly, so a row returned by the STALE
   * filter always reports {@code stale=true} and an ALL listing classifies identically.
   *
   * <p>Never-evaluated only counts as stale once the flag is itself older than the cutoff —
   * otherwise every flag created in the last minute would be reported stale on sight.
   */
  private static boolean isStale(
      FeatureFlag flag, LocalDateTime lastEvaluatedAt, LocalDateTime staleBefore) {
    if (lastEvaluatedAt == null) {
      return flag.getCreatedAt() != null && flag.getCreatedAt().isBefore(staleBefore);
    }
    return lastEvaluatedAt.isBefore(staleBefore);
  }

  private static boolean isExpired(FeatureFlag flag, LocalDateTime now) {
    return flag.getExpiresAt() != null && flag.getExpiresAt().isBefore(now);
  }
}
