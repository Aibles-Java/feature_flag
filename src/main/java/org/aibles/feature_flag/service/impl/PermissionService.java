package org.aibles.feature_flag.service.impl;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aibles.feature_flag.config.AppConfig;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Policy Decision Point for the Admin API. A user's effective permissions on a resource are the
 * union of their organization role and any scoped grant (built-in or custom role); {@link #check}
 * asserts the required action is in that set, then applies the production and change-window rules.
 *
 * <p>{@link #check} is the only way in. The pre-ABAC {@code requireRole*} adapters are gone: they
 * compared built-in roles, so a grant carrying a custom role could never satisfy one, and the
 * environment-scoped adapter loaded the environment purely for its project id and threw away the
 * {@code type} and change window the production rules are made of. Every call site still using them
 * was, by construction, invisible to half of this class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionService {

  private final OrganizationMemberRepository memberRepository;
  private final ProjectRepository projectRepository;
  private final EnvironmentRepository environmentRepository;
  private final PermissionGrantRepository grantRepository;

  /** The change-window clock (app.change-window.zone), not the general application Clock. */
  @Qualifier(AppConfig.CHANGE_WINDOW_CLOCK)
  private final Clock clock;

  private static final Map<MemberRole, Set<Action>> ROLE_ACTIONS = buildRoleActions();

  /**
   * Actions that change what a production SDK sees, mapped to the elevated action they require when
   * they do. Every way to alter production behaviour must appear here: archiving a flag hides it
   * from every evaluation response just as surely as toggling it off, and rotating or deleting an
   * environment cuts its SDKs off entirely, so guarding only {@code FLAG_STATE_UPDATE} would leave
   * the rule trivially bypassable.
   */
  private static final Map<Action, Action> PRODUCTION_ELEVATED =
      Map.ofEntries(
          Map.entry(Action.FLAG_STATE_UPDATE, Action.FLAG_STATE_UPDATE_PRODUCTION),
          Map.entry(Action.FLAG_ARCHIVE, Action.FLAG_ARCHIVE_PRODUCTION),
          Map.entry(Action.ENV_ROTATE_KEY, Action.ENV_ROTATE_KEY_PRODUCTION),
          Map.entry(Action.ENV_DELETE, Action.ENV_DELETE_PRODUCTION),
          Map.entry(Action.ENV_KEY_CREATE, Action.ENV_KEY_CREATE_PRODUCTION),
          Map.entry(Action.ENV_KEY_REVOKE, Action.ENV_KEY_REVOKE_PRODUCTION),
          // A webhook on a production environment streams every flag change, values included, to
          // a URL the subscriber picks. Creating, repointing, deleting or re-keying one changes
          // where production state goes, which is what this table is for.
          Map.entry(Action.WEBHOOK_MANAGE, Action.WEBHOOK_MANAGE_PRODUCTION));

  /**
   * Elevated actions that skip the change window (rule D) while still requiring the elevated
   * permission (rule B).
   *
   * <p>This is the <strong>only</strong> exception to rule D and it must stay that way unless the
   * same argument holds: revocation is monotonically restrictive — it can only remove access, never
   * grant it — so the window prevents no attack, while the delay it imposes is precisely the window
   * an attacker holding a leaked key wants. A key leaked at 03:00 has to be withdrawable at 03:00,
   * and widening the window is itself OWNER-gated, so a strict reading would leave no break-glass
   * path at all. Rotation is deliberately NOT here: it issues a new production credential, which is
   * a planned change and stays fully windowed.
   */
  private static final Set<Action> WINDOW_EXEMPT = Set.of(Action.ENV_KEY_REVOKE_PRODUCTION);

  private static Map<MemberRole, Set<Action>> buildRoleActions() {
    Set<Action> viewer =
        EnumSet.of(
            Action.FLAG_READ,
            Action.ENV_READ,
            Action.PROJECT_READ,
            Action.AUDIT_READ,
            Action.WEBHOOK_READ,
            // Reading the organisation and its member list used to be gated by a bare isMember
            // check, which no role or custom role could describe. Both sit at VIEWER so today's
            // behaviour is unchanged: anyone in the organisation can still see it.
            Action.ORG_READ,
            Action.MEMBER_READ);

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
            Action.ENV_KEY_CREATE,
            Action.ENV_KEY_REVOKE,
            Action.PROJECT_CREATE,
            Action.PROJECT_UPDATE,
            Action.ORG_UPDATE,
            Action.MEMBER_INVITE,
            Action.MEMBER_MANAGE,
            Action.GRANT_MANAGE,
            Action.ROLE_MANAGE,
            Action.ENV_EXPORT,
            Action.WEBHOOK_MANAGE));

    Set<Action> owner = EnumSet.copyOf(admin);
    owner.addAll(
        EnumSet.of(
            Action.FLAG_DELETE,
            Action.ENV_DELETE,
            Action.PROJECT_DELETE,
            Action.ORG_DELETE,
            Action.FLAG_STATE_UPDATE_PRODUCTION,
            Action.FLAG_ARCHIVE_PRODUCTION,
            Action.ENV_ROTATE_KEY_PRODUCTION,
            Action.ENV_DELETE_PRODUCTION,
            Action.ENV_MANAGE_PROTECTION,
            Action.ENV_KEY_CREATE_PRODUCTION,
            Action.ENV_KEY_REVOKE_PRODUCTION,
            Action.WEBHOOK_MANAGE_PRODUCTION));

    // MEMBER is not the empty set: someone who cannot read the organisation they belong to sees
    // it in the workspace switcher and then cannot open it. Project reach, and only project
    // reach, is what MEMBER gives up relative to VIEWER.
    Set<Action> member = EnumSet.of(Action.ORG_READ, Action.MEMBER_READ);

    return Map.of(
        MemberRole.MEMBER, Set.copyOf(member),
        MemberRole.VIEWER, Set.copyOf(viewer),
        MemberRole.ADMIN, Set.copyOf(admin),
        MemberRole.OWNER, Set.copyOf(owner));
  }

  public static Set<Action> actionsForRole(MemberRole role) {
    return role == null ? Set.of() : ROLE_ACTIONS.getOrDefault(role, Set.of());
  }

  /**
   * What a grant confers, plus {@code PROJECT_READ} whenever it confers anything at all.
   *
   * <p>Without that addition a custom role holding {@code FLAG_READ} and not {@code PROJECT_READ}
   * produces an incoherent state: the project is absent from the list and refuses a fetch by id,
   * while its flags are served normally. The screen looks empty and the API does not. Nobody
   * building a role is warned about it, and the omission is easy to make because {@code
   * PROJECT_READ} reads like an administrative permission rather than the price of seeing anything
   * inside the project.
   *
   * <p>An empty role stays empty. A grant that confers nothing should not confer visibility either
   * — that is the "grants nothing yet" case the role list already labels.
   */
  public static Set<Action> grantActions(PermissionGrant grant) {
    Set<Action> granted;
    if (grant.getRole() != null) {
      granted = actionsForRole(grant.getRole());
    } else {
      granted = grant.getCustomRole() != null ? grant.getCustomRole().getActions() : Set.of();
    }
    if (granted.isEmpty() || granted.contains(Action.PROJECT_READ)) {
      return granted;
    }
    Set<Action> withVisibility = EnumSet.copyOf(granted);
    withVisibility.add(Action.PROJECT_READ);
    return Set.copyOf(withVisibility);
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
  /** Whether the caller's organisation role alone carries this action, before any grant. */
  public boolean hasOrgAction(Action action, UUID orgId) {
    return effectiveActionsForOrg(currentUserId(), orgId).contains(action);
  }

  /**
   * Projects the caller reaches through a grant that confers {@code action}.
   *
   * <p>Only meaningful for a caller whose org role does not already carry the action org-wide — ask
   * {@link #hasOrgAction} first, or this will understate what they can see.
   */
  public Set<UUID> grantedProjectIds(Action action) {
    return grantRepository.findAllByUser_IdAndScopeType(currentUserId(), ScopeType.PROJECT).stream()
        .filter(grant -> grantActions(grant).contains(action))
        .map(PermissionGrant::getScopeId)
        .collect(java.util.stream.Collectors.toSet());
  }

  /**
   * Base-permission check at the scope of {@code resource}, with no production elevation or change
   * window. {@link #check} on a project-scoped ref for an action in {@link #PRODUCTION_ELEVATED}
   * demands the elevated action for every PRODUCTION env under the project, which is wrong as a
   * first gate when the caller targets one specific env (it would deny a plain STAGING update). Use
   * this as step (ii) of the F17 ordering, then {@link #check} with the verified env.
   *
   * <p><strong>Never the sole gate for a {@code PRODUCTION_ELEVATED} action</strong>: it skips the
   * production elevation and change window, so it must always be followed by {@link #check}.
   */
  public void checkScope(Action action, ResourceRef resource) {
    if (!effectiveActions(resource).contains(action)) {
      throw new UnauthorizedException("Insufficient permissions for action: " + action);
    }
  }

  public void check(Action action, ResourceRef resource) {
    List<Environment> productionEnvs = productionEnvironments(action, resource);
    Action required =
        productionEnvs.isEmpty() ? action : PRODUCTION_ELEVATED.getOrDefault(action, action);

    if (!effectiveActions(resource).contains(required)) {
      throw new UnauthorizedException(
          required == action
              ? "Insufficient permissions for action: " + action
              : action + " against a PRODUCTION environment requires elevated permission");
    }

    if (required != action
        && !WINDOW_EXEMPT.contains(required)
        && productionEnvs.stream().anyMatch(e -> !withinChangeWindow(e))) {
      throw new UnauthorizedException(
          "Production changes are only allowed within the configured change window");
    }
  }

  /**
   * The production environments {@code action} would affect — empty when it cannot affect any, in
   * which case no elevation applies.
   *
   * <p>Environment-scoped call sites name their target and only that one is considered. A
   * project-scoped one (archiving a flag) reaches every environment beneath the project, so every
   * production environment there is resolved and the strictest change window wins: one closed
   * window denies the action. The lookup is skipped entirely for actions outside {@link
   * #PRODUCTION_ELEVATED}, so the common path costs no extra query.
   */
  private List<Environment> productionEnvironments(Action action, ResourceRef resource) {
    if (!PRODUCTION_ELEVATED.containsKey(action)) {
      return List.of();
    }
    Environment target = resource.environment();
    if (target != null) {
      return target.getType() == EnvType.PRODUCTION ? List.of(target) : List.of();
    }
    if (resource.projectId() == null) {
      return List.of();
    }
    return environmentRepository.findAllByProjectId(resource.projectId()).stream()
        .filter(e -> e.getType() == EnvType.PRODUCTION)
        .toList();
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
    int hour = LocalTime.now(zonedClock(env)).getHour();
    return start < end ? (hour >= start && hour < end) : (hour >= start || hour < end);
  }

  /**
   * The clock to read the window in.
   *
   * <p>An unparseable zone falls back to the configured application zone (app.change-window.zone)
   * rather than throwing: the stored string is validated when it is set, so a bad value here means
   * data written before that validation existed, and refusing every production change until someone
   * fixes a row is a worse failure than reading the window in the wrong zone.
   */
  private Clock zonedClock(Environment env) {
    String zone = env.getChangeWindowTimezone();
    if (zone == null || zone.isBlank()) {
      return clock;
    }
    try {
      return clock.withZone(ZoneId.of(zone));
    } catch (DateTimeException e) {
      log.warn(
          "Environment {} has an unusable change-window timezone {}; falling back to the configured change-window zone ({})",
          env.getId(),
          zone,
          clock.getZone());
      return clock;
    }
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
