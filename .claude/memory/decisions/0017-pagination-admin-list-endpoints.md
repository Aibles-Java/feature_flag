# 0017 — Offset pagination for admin list endpoints (issue #33)

**What:** Bounded, consistent pagination on all admin list endpoints. See `docs/adr/ADR-0003-pagination-strategy.md` for the full ADR.

## Decisions
- **Offset via Spring Data `Pageable`** (not cursor — heavier contract, unnecessary at this scale).
  Controller `@ParameterObject @PageableDefault(size=20, sort={"createdAt","id"}) Pageable` →
  service returns `Page<XxxResponse>` (`.map(this::toResponse)`) → repository paginated method.
- **`PageResponse<T>` envelope** `{content, page, size, totalElements, totalPages}` — hand-rolled,
  NOT Spring's `Page` serialized (its JSON isn't contract-stable). **Only list endpoints wrapped**;
  single-object endpoints stay bare DTOs. **Breaking**: list JSON went from bare `[...]` to `{content:...}`.
- **Max-size enforcement** via `config/PaginationConfig` — a
  `PageableHandlerMethodArgumentResolverCustomizer` bean sets `maxPageSize=100`, fallback size 20.
  Constants `PaginationConfig.MAX_PAGE_SIZE`/`DEFAULT_PAGE_SIZE` reused by the standalone MockMvc
  tests' hand-built resolver so test limits can't drift from prod. This bean is the *actual* clamp
  (a `size=500` request → 100); auto-detected by `SpringDataWebAutoConfiguration`.
- **Deterministic sort `createdAt,id`** (unique `id` tiebreak → no flaky order, offset-safe). All 7
  entities have both columns.
- **SDK eval endpoint EXCLUDED** (`/api/v1/sdk/flags` stays a bare list — SDKs need the full set).
- **6 endpoints paginated**: orgs `listMine`, org `listMembers`, projects, environments, active
  flags, archived flags.

## Implementation notes / gotchas
- **`listMine` special case**: resolves membership org-ids first (small set) then
  `organizationRepository.findByIdIn(ids, pageable)` so the sort binds to `Organization`'s own
  columns (not the join table). Empty ids → `Page.empty(pageable)` (no invalid empty-`IN`).
- **`EnvironmentRepository.findAllByProjectId` is OVERLOADED**: kept the unbounded `List` version
  (flag creation auto-creates a FlagEnvironmentState per env → needs ALL envs) AND added a
  `Page(..., Pageable)` version for the list endpoint. Same for the two `FeatureFlagRepository`
  archived-false/true methods and `OrganizationMemberRepository.findAllByOrganizationId` (repo
  tests still assert the `List` versions). Only `ProjectRepository` was replaced outright.
- **Test surgery**: 6 controller list tests → stub `PageImpl`, assert `$.content[...]`, register a
  `PageableHandlerMethodArgumentResolver` in `standaloneSetup`; 5 service tests → stub the
  2-arg repo method, return/assert `Page`. New tests: clamp-to-100 + default-20 + deterministic-sort
  (ProjectControllerTest, via `ArgumentCaptor<Pageable>`), and a real-H2
  `FeatureFlagRepositoryTest` paginate+sort (walks all pages → each key once, gap-free).

## Follow-up surfaced by code review (NOT fixed here — belongs to #52)
- **`OrganizationServiceImpl.listMembers` → likely root cause of bug #52** (`GET
  /organisations/{id}/members` 500s). `toMemberResponse` reads lazy `m.getUser().getEmail()`
  outside any transaction (`spring.jpa.open-in-view=false`, method not `@Transactional`) →
  `LazyInitializationException`. **Pre-existing** — #33 neither introduced nor worsened it (old code
  accessed the same lazy fields). Fix in #52: `@Transactional(readOnly=true)` on the read path, or a
  `JOIN FETCH om.user` query — with an integration test that actually hits the endpoint against a
  real DB (the mocked service/controller tests can't catch it). See [[gh-cli-off-path-location]].
