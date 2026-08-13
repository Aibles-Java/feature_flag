package org.aibles.feature_flag.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

/**
 * Tier-1 architecture governance (issue #48, spec {@code docs/specs/codegraph-adoption.md} §A.4).
 *
 * <p>Turns the prose invariants in {@code CLAUDE.md} into build-gating assertions over bytecode.
 * These run under Surefire, so a violation fails {@code ./mvnw test} and {@code ./mvnw verify}
 * exactly like Spotless and the JaCoCo ratchet — no pipeline change required.
 *
 * <p>Scope honesty: ArchUnit reasons over <em>dependency and access edges</em> only. It is exact
 * for structural rules and cannot prove statement ordering, per-method guard coverage, or data
 * flow. The invariants it cannot soundly express are listed as Tier 3 in the spec and are
 * deliberately <em>not</em> claimed here.
 */
@AnalyzeClasses(
    packages = "org.aibles.feature_flag",
    importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class ArchitectureTest {

  private static final String ROOT = "org.aibles.feature_flag";
  private static final String PERMISSION_SERVICE = ROOT + ".service.impl.PermissionService";
  private static final String MEMBER_ROLE = ROOT + ".domain.enums.MemberRole";
  private static final String USER_PRINCIPAL = ROOT + ".security.UserPrincipal";
  private static final String FEATURE_FLAG = ROOT + ".domain.entity.FeatureFlag";
  private static final String SECURITY_CONTEXT_HOLDER =
      "org.springframework.security.core.context.SecurityContextHolder";

  /**
   * R1 — Layering: Controller → Service → Repository, no skips and no back-edges.
   *
   * <p>{@code Security} and {@code Config} are declared as layers rather than left outside because
   * both legitimately reach the repository layer <em>before</em> any service exists: {@link
   * org.aibles.feature_flag.security.ApiKeyAuthenticationFilter} and {@code
   * CustomUserDetailsService} resolve the authentication principal from the database, and {@code
   * SecurityConfig} injects that repository into the filter. Every other package remains barred
   * from the repository layer.
   */
  @ArchTest
  static final ArchRule r1LayeringIsRespected =
      layeredArchitecture()
          .consideringAllDependencies()
          .layer("Controller")
          .definedBy("..controller..")
          .layer("Service")
          .definedBy("..service..")
          .layer("Repository")
          .definedBy("..repository..")
          .layer("Security")
          .definedBy("..security..")
          .layer("Config")
          .definedBy("..config..")
          .whereLayer("Controller")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Repository")
          .mayOnlyBeAccessedByLayers("Service", "Security", "Config")
          .as("R1: Controller -> Service -> Repository layering is respected");

  /** R2 — Controllers must never talk to a repository directly (the invariant made explicit). */
  @ArchTest
  static final ArchRule r2ControllersDoNotUseRepositories =
      noClasses()
          .that()
          .resideInAPackage("..controller..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..repository..")
          .as("R2: controllers must not depend on the repository layer");

  /**
   * R3 — No authorization logic in controllers. Role checks live in the service layer via {@code
   * PermissionService}; a controller that names {@code PermissionService} or {@code MemberRole} is
   * making an authorization decision at the wrong altitude.
   */
  @ArchTest
  static final ArchRule r3ControllersHaveNoAuthzLogic =
      noClasses()
          .that()
          .resideInAPackage("..controller..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName(PERMISSION_SERVICE)
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName(MEMBER_ROLE)
          .as("R3: controllers must not contain authorization logic");

  /**
   * R4 — {@code SecurityContextHolder} access is centralized.
   *
   * <p>Two authentication chains write different principal types into the same holder ({@code
   * UserPrincipal} for the admin chain, {@code Environment} for the SDK chain), so an ad-hoc read
   * elsewhere is a cast-unsafe way to mix them. Only {@code PermissionService} (the single admin
   * reader) and the {@code security} package itself — the filters whose job is to <em>populate</em>
   * the holder — may touch it. ArchUnit sees a static method call and cannot distinguish a read
   * from a write, which is why the filters' package is exempted wholesale rather than per-method.
   *
   * <p>The spec's sketch also exempted the SDK {@code EvaluationController}. It is deliberately
   * <em>not</em> exempted here: that controller receives its principal as an injected {@code
   * Authentication} method parameter and never touches the holder, so the allowance would be dead
   * weight that quietly licenses a future regression. Controllers wanting the principal should take
   * the parameter, and this rule now enforces that.
   */
  @ArchTest
  static final ArchRule r4SecurityContextAccessIsCentralized =
      noClasses()
          .that()
          .doNotHaveFullyQualifiedName(PERMISSION_SERVICE)
          .and()
          .resideOutsideOfPackage("..security..")
          .should()
          .accessClassesThat()
          .haveFullyQualifiedName(SECURITY_CONTEXT_HOLDER)
          .as(
              "R4: only PermissionService and the security filters may access SecurityContextHolder");

  /**
   * R5 — The two auth chains' principal types must not cross. {@code UserPrincipal} belongs to the
   * JWT admin chain; the SDK chain authenticates an {@code Environment} by API key and must never
   * reference it.
   */
  @ArchTest
  static final ArchRule r5UserPrincipalStaysOutOfSdk =
      noClasses()
          .that()
          .resideInAPackage("..controller.sdk..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName(USER_PRINCIPAL)
          .as("R5: the SDK chain must not depend on the admin chain's UserPrincipal");

  /**
   * R6 — The repository layer is Spring Data interfaces, never hand-rolled classes.
   *
   * <p>Deliberately asserted over <em>every</em> type in {@code ..repository..}, not only those
   * named {@code *Repository}: a hand-rolled DAO or JDBC wrapper dropped into the package under any
   * other name is exactly the thing this rule exists to catch, and a name-filtered version would
   * wave it through.
   *
   * <p>Consequence to know before "fixing" a failure: a Spring Data <em>custom fragment</em>
   * implementation (the {@code XRepositoryImpl} pattern) is a class and will trip this rule. That
   * is intended — adopting custom fragments is an architecture decision that should be made
   * deliberately and recorded, not introduced by a file landing in the package.
   */
  @ArchTest
  static final ArchRule r6RepositoriesAreInterfaces =
      classes()
          .that()
          .resideInAPackage("..repository..")
          .should()
          .beInterfaces()
          .as("R6: every type in the repository layer must be an interface");

  /**
   * R7 — No dependency cycles between the top-level package slices.
   *
   * <p>Frozen against a committed baseline ({@code src/test/resources/archunit_store}) because one
   * cycle pre-dates this rule: {@code config} → {@code security} ({@code SecurityConfig} constructs
   * the filter beans) and {@code security} → {@code config} ({@code JwtTokenProvider} reads {@code
   * JwtProperties}). Untangling that means moving the JWT configuration properties, which is a
   * change to a sensitive area and out of scope here. Freezing gates <em>new</em> cycles today
   * rather than deferring the whole rule; see ADR-0004.
   */
  @ArchTest
  static final ArchRule r7NoPackageCycles =
      FreezingArchRule.freeze(
          slices()
              .matching(ROOT + ".(*)..")
              .should()
              .beFreeOfCycles()
              .as("R7: top-level package slices must be free of cycles"));

  // ---------------------------------------------------------------------------------------------
  // Tier 2 (issue #49) — the immutable-flag-key invariant, via custom conditions.
  //
  // Read this before editing R8/R9: the spec's Tier-2 sketch (§A.4) proposes "assert no method sets
  // the FeatureFlag.key field" and "assert update() does not call UpdateFeatureFlagRequest
  // .getKey()". Both are VACUOUS against this codebase, verified in bytecode:
  //
  //   * `key` is a private field, and Lombok's @Setter generates setKey INSIDE FeatureFlag. javap
  //     shows the only two `putfield key` sites are FeatureFlag.setKey and FeatureFlag's all-args
  //     constructor. No other class can write the field — Java access control already guarantees
  //     it — so a field-set rule could never fail. The mistake that IS possible is calling the
  //     generated setter, which is a method call, not a field access.
  //   * UpdateFeatureFlagRequest declares no `key` field at all, so `getKey()` does not exist. A
  //     rule forbidding a call to a non-existent method can never fire, and cannot even be written
  //     against a typed reference.
  //
  // R8/R9 below enforce the same *intent* at the points where it can actually be broken.
  // ---------------------------------------------------------------------------------------------

  /**
   * Detects any mutation of {@code FeatureFlag.key} from outside the entity — both a direct field
   * write and a call to the Lombok-generated {@code setKey}. The method-call half is the one that
   * fires in practice; the field-access half is a backstop for a future hand-written mutator or a
   * nested class.
   */
  private static final ArchCondition<JavaClass> MUTATE_FEATURE_FLAG_KEY =
      new ArchCondition<>("mutate FeatureFlag.key") {
        @Override
        public void check(JavaClass item, ConditionEvents events) {
          for (JavaFieldAccess access : item.getFieldAccessesFromSelf()) {
            if (access.getAccessType() == JavaFieldAccess.AccessType.SET
                && FEATURE_FLAG.equals(access.getTargetOwner().getFullName())
                && "key".equals(access.getTarget().getName())) {
              events.add(SimpleConditionEvent.satisfied(item, "writes FeatureFlag.key: " + access));
            }
          }
          for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
            if (FEATURE_FLAG.equals(call.getTargetOwner().getFullName())
                && "setKey".equals(call.getTarget().getName())) {
              events.add(SimpleConditionEvent.satisfied(item, "calls FeatureFlag.setKey: " + call));
            }
          }
        }
      };

  /**
   * R8 — {@code FeatureFlag.key} is immutable after creation. Only the entity itself may write it
   * (its constructor, which is what the Lombok builder ultimately calls). The database already
   * enforces this via {@code @Column(updatable = false)}; this rule moves the boundary into the
   * code, so an accidental {@code flag.setKey(...)} fails the build instead of being silently
   * dropped at flush time — a failure mode that is otherwise invisible until an SDK notices the key
   * it caches no longer matches.
   */
  @ArchTest
  static final ArchRule r8FlagKeyIsNeverMutated =
      noClasses()
          .that()
          .doNotHaveFullyQualifiedName(FEATURE_FLAG)
          .should(MUTATE_FEATURE_FLAG_KEY)
          .as("R8: FeatureFlag.key must never be mutated outside the entity itself");

  /**
   * R9 — The update request must not carry a key at all.
   *
   * <p>This is the sound form of "update() must ignore the key". Reintroducing a {@code key} field
   * on {@code UpdateFeatureFlagRequest} is the first step of the mistake and the only one a static
   * rule can catch: Lombok's {@code @Data} would immediately generate {@code getKey()}, the field
   * would bind from request JSON, and a plausible-looking {@code if (request.getKey() != null)}
   * line becomes one edit away. Blocking the field blocks the whole path, and does so at the point
   * where a reviewer can still see the intent.
   */
  @ArchTest
  static final ArchRule r9UpdateRequestCarriesNoKey =
      noFields()
          .that()
          .areDeclaredInClassesThat()
          .haveFullyQualifiedName(ROOT + ".dto.request.UpdateFeatureFlagRequest")
          .should()
          .haveName("key")
          .as("R9: UpdateFeatureFlagRequest must not declare a key field (the key is immutable)");
}
