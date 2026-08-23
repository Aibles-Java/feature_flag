package org.aibles.feature_flag.service.impl;

import java.time.Clock;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.domain.entity.Environment;
import org.aibles.feature_flag.domain.entity.OrganizationMember;
import org.aibles.feature_flag.domain.entity.PermissionGrant;
import org.aibles.feature_flag.domain.entity.Project;
import org.aibles.feature_flag.domain.enums.Action;
import org.aibles.feature_flag.domain.enums.EnvType;
import org.aibles.feature_flag.domain.enums.MemberRole;
import org.aibles.feature_flag.domain.enums.ScopeType;
import org.aibles.feature_flag.exception.ResourceNotFoundException;
import org.aibles.feature_flag.exception.UnauthorizedException;
import org.aibles.feature_flag.repository.EnvironmentRepository;
import org.aibles.feature_flag.repository.OrganizationMemberRepository;
import org.aibles.feature_flag.repository.PermissionGrantRepository;
import org.aibles.feature_flag.repository.ProjectRepository;
import org.aibles.feature_flag.security.UserPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Policy Decision Point for the Admin API. A user's effective permissions on a resource are the
 * union of their organization role and any scoped grant (built-in or custom role); {@link #check}
 * asserts the required action is in that set, then applies the production and change-window rules.
 *
 * <p>The {@code requireRole*} methods are the pre-ABAC API, kept as adapters so call sites migrate
 * to {@link #check} incrementally. The project- and environment-scoped adapters are grant-aware: a
 * PROJECT grant carrying a built-in role elevates the caller. A grant carrying a <em>custom</em>
 * role has no built-in role to compare against, so it does not satisfy an adapter — those paths
 * must use {@link #check}.
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

  private final OrganizationMemberRepository memberRepository;
  private final ProjectRepository projectRepository;
  private final EnvironmentRepository environmentRepository;
  private final PermissionGrantRepository grantRepository;
  private final Clock clock;

  private static final Map<MemberRole, Set<Action>> ROLE_ACTIONS = buildRoleActions();

  private static Map<MemberRole, Set<Action>> buildRoleActions() {
    Set<Action> viewer =
        EnumSet.of(Action.FLAG_READ, Action.ENV_READ, Action.PROJECT_READ, Action.AUDIT_READ);

    Set<Action> admin = EnumSet.copyOf(viewer);
    admin.addAll(
        EnumSet.of(
            Action.FLAG_CREATE,
            Action.FLAG_UPDATE,
            Action.FLAG_ARCHIVE,
            Action.FLAG_STATE_UPDATE,
            Action.ENV_CREATE,
            Action.ENV_UPDATE,
            Action.ENV_ROTATE_KEY,
            Action.PROJECT_CREATE,
            Action.PROJECT_UPDATE,
            Action.ORG_UPDATE,
            Action.MEMBER_INVITE,
            Action.MEMBER_MANAGE,
            Action.GRANT_MANAGE,
            Action.ROLE_MANAGE));

    Set<Action> owner = EnumSet.copyOf(admin);
    owner.addAll(
        EnumSet.of(
            Action.FLAG_DELETE,
            Action.ENV_DELETE,
            Action.PROJECT_DELETE,
            Action.ORG_DELETE,
            Action.FLAG_STATE_UPDATE_PRODUCTION,
            Action.ENV_MANAGE_PROTECTION));

    return Map.of(
        MemberRole.VIEWER, Set.copyOf(viewer),
        MemberRole.ADMIN, Set.copyOf(admin),
        MemberRole.OWNER, Set.copyOf(owner));
  }

  public static Set<Action> actionsForRole(MemberRole role) {
    return role == null ? Set.of() : ROLE_ACTIONS.getOrDefault(role, Set.of());
  }

  public static Set<Action> grantActions(PermissionGrant grant) {
    if (grant.getRole() != null) {
      return actionsForRole(grant.getRole());
    }
    return grant.getCustomRole() != null ? grant.getCustomRole().getActions() : Set.of();
  }

  public UUID currentUserId() {
    UserPrincipal principal =
        (UserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return principal.getId();
  }

  public String currentUserEmail() {
    UserPrincipal principal =
        (UserPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    return principal.getEmail();
  }

  public boolean isMember(UUID orgId) {
    return memberRepository.existsByOrganizationIdAndUserId(orgId, currentUserId());
  }

  public MemberRole orgRole(UUID userId, UUID orgId) {
    return memberRepository
        .findByOrganizationIdAndUserId(orgId, userId)
        .map(OrganizationMember::getRole)
        .orElse(null);
  }

  public Set<Action> effectiveActionsForOrg(UUID userId, UUID orgId) {
    return new HashSet<>(actionsForRole(orgRole(userId, orgId)));
  }

  public Set<Action> effectiveActionsForProject(UUID userId, UUID projectId) {
    Project project =
        projectRepository
            .findById(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

    Set<Action> actions =
        new HashSet<>(actionsForRole(orgRole(userId, project.getOrganization().getId())));
    grantRepository
        .findByUser_IdAndScopeTypeAndScopeId(userId, ScopeType.PROJECT, projectId)
        .ifPresent(grant -> actions.addAll(grantActions(grant)));
    return actions;
  }

  public Set<Action> effectiveActions(ResourceRef resource) {
    UUID userId = currentUserId();
    return resource.projectId() != null
        ? effectiveActionsForProject(userId, resource.projectId())
        : effectiveActionsForOrg(userId, resource.orgId());
  }

  /**
   * Authorizes {@code action} against {@code resource}, throwing {@link UnauthorizedException} on
   * deny.
   */
  public void check(Action action, ResourceRef resource) {
    Environment env = resource.environment();
    boolean prodEnv = env != null && env.getType() == EnvType.PRODUCTION;

    Action required =
        action == Action.FLAG_STATE_UPDATE && prodEnv
            ? Action.FLAG_STATE_UPDATE_PRODUCTION
            : action;

    if (!effectiveActions(resource).contains(required)) {
      throw new UnauthorizedException(
          required == Action.FLAG_STATE_UPDATE_PRODUCTION
              ? "Changing flag state in a PRODUCTION environment requires elevated permission"
              : "Insufficient permissions for action: " + action);
    }

    if (required == Action.FLAG_STATE_UPDATE_PRODUCTION
        && env != null
        && !withinChangeWindow(env)) {
      throw new UnauthorizedException(
          "Production flag-state changes are only allowed within the configured change window");
    }
  }

  /**
   * A window [start, end) may wrap past midnight; an absent or zero-width window imposes no
   * restriction.
   */
  private boolean withinChangeWindow(Environment env) {
    Integer start = env.getChangeWindowStartHour();
    Integer end = env.getChangeWindowEndHour();
    if (start == null || end == null || start.equals(end)) {
      return true;
    }
    int hour = LocalTime.now(clock).getHour();
    return start < end ? (hour >= start && hour < end) : (hour >= start || hour < end);
  }

  /** Org-scope adapter. Project grants deliberately do not apply at org scope. */
  public void requireRole(UUID orgId, MemberRole... roles) {
    MemberRole role = orgRole(currentUserId(), orgId);
    if (role == null) {
      throw new UnauthorizedException("You are not a member of this organisation");
    }
    if (Arrays.stream(roles).noneMatch(r -> r == role)) {
      throw new UnauthorizedException(
          "Insufficient permissions. Required: " + Arrays.toString(roles));
    }
  }

  /** Project-scope adapter — a built-in-role PROJECT grant elevates the caller. */
  public void requireRoleForProject(UUID projectId, MemberRole... roles) {
    MemberRole role = effectiveRoleForProject(currentUserId(), projectId);
    if (role == null) {
      throw new UnauthorizedException("You are not a member of this organisation");
    }
    if (Arrays.stream(roles).noneMatch(r -> r == role)) {
      throw new UnauthorizedException(
          "Insufficient permissions. Required: " + Arrays.toString(roles));
    }
  }

  public void requireRoleForEnvironment(UUID environmentId, MemberRole... roles) {
    Environment env =
        environmentRepository
            .findById(environmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
    requireRoleForProject(env.getProject().getId(), roles);
  }

  /**
   * The more permissive of the caller's org role and any built-in-role PROJECT grant. Grants only
   * elevate: an org OWNER/ADMIN is never downgraded by a narrower grant.
   */
  public MemberRole effectiveRoleForProject(UUID userId, UUID projectId) {
    Project project =
        projectRepository
            .findById(projectId)
            .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));

    MemberRole role = orgRole(userId, project.getOrganization().getId());
    MemberRole granted =
        grantRepository
            .findByUser_IdAndScopeTypeAndScopeId(userId, ScopeType.PROJECT, projectId)
            .map(PermissionGrant::getRole)
            .orElse(null);
    return mostPermissive(role, granted);
  }

  private static MemberRole mostPermissive(MemberRole a, MemberRole b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return actionsForRole(a).size() >= actionsForRole(b).size() ? a : b;
  }

  /**
   * The resource an action targets. {@code projectId} (when set) resolves the subject's actions,
   * otherwise {@code orgId}; {@code environment} carries the attributes the production rules read.
   */
  public record ResourceRef(UUID orgId, UUID projectId, Environment environment) {

    public static ResourceRef org(UUID orgId) {
      return new ResourceRef(orgId, null, null);
    }

    public static ResourceRef project(UUID projectId) {
      return new ResourceRef(null, projectId, null);
    }

    public static ResourceRef environment(UUID projectId, Environment environment) {
      return new ResourceRef(null, projectId, environment);
    }
  }
}
