# ADR-0003: Pagination Strategy for Admin List Endpoints

**Status:** Accepted
**Date:** 2026-07-15

## Context

Every admin list endpoint (organisations, members, projects, environments, flags, archived flags)
returned an unbounded collection — a bare top-level JSON array with no limit. As tenants grow (an
org with thousands of flags, or the forthcoming append-only `audit_log` in issue #31), a single
request could load an entire table into memory and serialize it, risking OOM and slow responses.
Issue #33 requires bounded, consistent pagination across these endpoints, while explicitly
**excluding** the SDK evaluation endpoint (`GET /api/v1/sdk/flags`) — SDKs need the full flag set
for an environment in one call.

Two questions had to be settled: **offset vs. cursor** pagination, and the **response shape**.

## Decision

**Offset-based pagination via Spring Data `Pageable`.** Controllers accept a `Pageable` argument
(`page`, `size`, `sort`); services pass it to repository methods that return `Page<Entity>`; the
result is mapped to DTOs and wrapped in a response envelope.

**Bounded page size.** A global `PageableHandlerMethodArgumentResolverCustomizer`
(`config/PaginationConfig`) enforces a default page size of **20** and a hard maximum of **100** —
a request for `size=500` is clamped to 100. This is the actual "enforced max" required by the
acceptance criteria and applies to every `Pageable` argument at once.

**Deterministic default sort.** Each list endpoint declares
`@PageableDefault(size = 20, sort = {"createdAt", "id"})`. Sorting by `createdAt` then the unique
`id` guarantees a stable total order (no flaky ordering when `createdAt` values collide), which is
also a correctness prerequisite for offset paging.

**Stable response envelope.** A hand-rolled `PageResponse<T>` — `{ content, page, size,
totalElements, totalPages }` — rather than serializing Spring Data's `Page` directly (whose JSON
form is verbose and not contract-stable across Spring versions). Only list endpoints are wrapped;
single-object endpoints keep returning bare DTOs.

**`listMine` (organisations) special case.** It resolves the user's org ids from membership first
(a small, membership-bounded set) then pages the organisations via `findByIdIn(ids, pageable)`, so
the sort applies to the `Organization` root's own `createdAt`/`id` rather than the join table's.

## Consequences

- **Good:** No admin list can return an unbounded result; responses are small and predictable.
  Consistent envelope across all six endpoints. `springdoc` documents the `page`/`size`/`sort`
  params automatically via `@ParameterObject`. No schema change — pure API/query-layer work.
- **Bad / breaking:** The list endpoints' JSON shape changed from a bare array (`[...]`) to
  `{ content: [...], ... }`. Existing API consumers and tests must read `content`. This is a
  deliberate, one-time breaking change taken now (pre-1.0) rather than later.
- Offset paging can drift if rows are inserted/deleted between page fetches, and deep offsets are
  less efficient than cursors on very large tables. Acceptable at current scale; see below.

## Alternatives Considered

- **Cursor-based (keyset) pagination.** More stable under concurrent writes and faster for deep
  pages. Rejected for now: heavier client contract (opaque cursors), more complex repository
  queries, and unnecessary at current data volumes. If the `audit_log` (issue #31) or a large-org
  flag list later demands it, a specific endpoint can adopt keyset paging without changing the
  others — this ADR does not preclude that.
- **Serialize Spring Data `Page` directly.** Rejected: its JSON form is verbose and has shifted
  across Spring versions (the `PagedModel` changes), making it a poor stable public contract.
- **No maximum page size (default only).** Rejected: a caller could pass `size=1000000` and defeat
  the entire purpose of paginating.
