package org.aibles.feature_flag.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.aibles.feature_flag.config.HygieneProperties;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.FeatureFlag;
import org.aibles.feature_flag.domain.entity.FlagEnvironmentState;
import org.aibles.feature_flag.domain.enums.HygieneStatus;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.dto.response.FlagHygieneResponse;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.FlagEnvironmentStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FlagHygieneServiceImplTest {

  @Mock FlagEnvironmentStateRepository flagStateRepository;
  @Mock PermissionService permissionService;

  FlagHygieneServiceImpl service;

  UUID projectId = UUID.randomUUID();
  Pageable pageable = PageRequest.of(0, 20);

  @BeforeEach
  void setUp() {
    service =
        new FlagHygieneServiceImpl(
            flagStateRepository,
            new HygieneProperties(Duration.ofDays(30), Duration.ofMinutes(5)),
            permissionService);
    doNothing().when(permissionService).requireRoleForProject(any(), any(MemberRole[].class));
  }

  private FlagEnvironmentState row(
      String key, LocalDateTime lastEvaluatedAt, LocalDateTime expiresAt, LocalDateTime createdAt) {
    FeatureFlag flag =
        FeatureFlag.builder()
            .id(UUID.randomUUID())
            .name(key)
            .key(key)
            .archived(false)
            .expiresAt(expiresAt)
            .createdAt(createdAt)
            .build();
    return FlagEnvironmentState.builder()
        .id(UUID.randomUUID())
        .featureFlag(flag)
        .environment(Environment.builder().id(UUID.randomUUID()).name("production").build())
        .enabled(true)
        .lastEvaluatedAt(lastEvaluatedAt)
        .build();
  }

  private void stubAll(FlagEnvironmentState... rows) {
    when(flagStateRepository.findHygieneRows(eq(projectId), any()))
        .thenReturn(new PageImpl<>(List.of(rows)));
  }

  // --- classification -------------------------------------------------------------------------

  @Test
  @DisplayName("a pair not evaluated within stale-after is reported stale, with a day count")
  void marksOldEvaluationsStale() {
    stubAll(row("old", LocalDateTime.now().minusDays(45), null, LocalDateTime.now().minusDays(90)));

    FlagHygieneResponse response = first(HygieneStatus.ALL);

    assertThat(response.isStale()).isTrue();
    assertThat(response.getDaysSinceLastEvaluation()).isEqualTo(45);
  }

  @Test
  void recentlyEvaluatedIsNotStale() {
    stubAll(row("hot", LocalDateTime.now().minusHours(1), null, LocalDateTime.now().minusDays(90)));

    assertThat(first(HygieneStatus.ALL).isStale()).isFalse();
  }

  @Test
  @DisplayName("an old never-evaluated flag is stale; a brand-new one is not")
  void neverEvaluatedDependsOnFlagAge() {
    stubAll(row("old-unused", null, null, LocalDateTime.now().minusDays(90)));
    assertThat(first(HygieneStatus.ALL).isStale()).isTrue();

    stubAll(row("brand-new", null, null, LocalDateTime.now()));
    FlagHygieneResponse fresh = first(HygieneStatus.ALL);
    assertThat(fresh.isStale()).isFalse();
    assertThat(fresh.getDaysSinceLastEvaluation()).isNull();
  }

  @Test
  void marksPastExpiryExpired() {
    stubAll(
        row("gone", LocalDateTime.now(), LocalDateTime.now().minusDays(1), LocalDateTime.now()));

    assertThat(first(HygieneStatus.ALL).isExpired()).isTrue();
  }

  @Test
  void futureAndAbsentExpiryAreNotExpired() {
    stubAll(
        row("later", LocalDateTime.now(), LocalDateTime.now().plusDays(30), LocalDateTime.now()));
    assertThat(first(HygieneStatus.ALL).isExpired()).isFalse();

    stubAll(row("never", LocalDateTime.now(), null, LocalDateTime.now()));
    assertThat(first(HygieneStatus.ALL).isExpired()).isFalse();
  }

  /** An expired flag still switched on is the case an operator actually needs to see. */
  @Test
  @DisplayName("rows carry the per-environment enabled state alongside expiry")
  void exposesEnabledStatePerEnvironment() {
    stubAll(
        row("live", LocalDateTime.now(), LocalDateTime.now().minusDays(1), LocalDateTime.now()));

    FlagHygieneResponse response = first(HygieneStatus.ALL);

    assertThat(response.isExpired()).isTrue();
    assertThat(response.isEnabled()).isTrue();
    assertThat(response.getEnvironmentName()).isEqualTo("production");
  }

  // --- filter routing -------------------------------------------------------------------------

  @Test
  @DisplayName("each status routes to its own query, so filtering happens in SQL not in memory")
  void routesEachStatusToItsQuery() {
    when(flagStateRepository.findStaleHygieneRows(eq(projectId), any(), any()))
        .thenReturn(Page.empty());
    when(flagStateRepository.findExpiredHygieneRows(eq(projectId), any(), any()))
        .thenReturn(Page.empty());
    when(flagStateRepository.findHygieneRows(eq(projectId), any())).thenReturn(Page.empty());

    service.report(projectId, HygieneStatus.STALE, pageable);
    verify(flagStateRepository).findStaleHygieneRows(eq(projectId), any(), any());

    service.report(projectId, HygieneStatus.EXPIRED, pageable);
    verify(flagStateRepository).findExpiredHygieneRows(eq(projectId), any(), any());

    service.report(projectId, HygieneStatus.ALL, pageable);
    verify(flagStateRepository).findHygieneRows(eq(projectId), any());
  }

  /**
   * The STALE query and the per-row {@code stale} flag must agree — a row returned by the filter
   * that then reports {@code stale=false} would be an obvious contradiction to an operator.
   */
  @Test
  @DisplayName("rows returned by the STALE filter report stale=true")
  void staleFilterAndRowFlagAgree() {
    when(flagStateRepository.findStaleHygieneRows(eq(projectId), any(), any()))
        .thenReturn(
            new PageImpl<>(
                List.of(
                    row("old", LocalDateTime.now().minusDays(45), null, LocalDateTime.now()),
                    row("never", null, null, LocalDateTime.now().minusDays(90)))));

    assertThat(service.report(projectId, HygieneStatus.STALE, pageable).getContent())
        .allMatch(FlagHygieneResponse::isStale);
  }

  // --- permissions ----------------------------------------------------------------------------

  @Test
  void requiresProjectMembership() {
    doThrow(new UnauthorizedException("nope"))
        .when(permissionService)
        .requireRoleForProject(any(), any(MemberRole[].class));

    assertThatThrownBy(() -> service.report(projectId, HygieneStatus.ALL, pageable))
        .isInstanceOf(UnauthorizedException.class);
    verify(flagStateRepository, never()).findHygieneRows(any(), any());
  }

  @Test
  @DisplayName("VIEWER is enough — the report is read-only")
  void allowsViewer() {
    stubAll(row("any", LocalDateTime.now(), null, LocalDateTime.now()));

    service.report(projectId, HygieneStatus.ALL, pageable);

    verify(permissionService)
        .requireRoleForProject(projectId, MemberRole.OWNER, MemberRole.ADMIN, MemberRole.VIEWER);
  }

  private FlagHygieneResponse first(HygieneStatus status) {
    return service.report(projectId, status, pageable).getContent().get(0);
  }
}
