# JPA / Hibernate Code Review — Critical Rules

Each rule: detection pattern, rationale, severity, source. Cross-reference fixes in `fix-recipes.md`.

---

## R01 — `@ManyToMany` with `CascadeType.ALL` or `CascadeType.REMOVE`

- **Severity**: CRITICAL
- **Detect** (regex): `@ManyToMany\s*\([^)]*cascade\s*=\s*[^)]*(?:CascadeType\.ALL|CascadeType\.REMOVE)`
- **Why**: Deletes the *associated* entities and any other associations they may have. A `Post` deleting its `Tag`s also wipes `Tag`s shared by other `Post`s. Cascading `REMOVE` is virtually never what you want on a many-to-many.
- **Source**: `the-best-way-to-use-the-manytomany-annotation-with-jpa-and-hibernate.md`, `a-beginners-guide-to-jpa-and-hibernate-cascade-types.md`
- **Allowed cascade types on `@ManyToMany`**: `PERSIST`, `MERGE` only. Never `ALL`, never `REMOVE`.

## R02 — Unidirectional `@OneToMany(cascade = CascadeType.ALL)` without `mappedBy`

- **Severity**: HIGH
- **Detect**: `@OneToMany` with `cascade` set, and **no** `mappedBy` attribute, and the field has no `@JoinColumn`.
- **Why**: Creates a separate join table even though the relation is logically a parent-child. Each child insert costs 2 SQL statements (insert child, insert join row). Updates and deletes are pathological.
- **Source**: `the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate.md`
- **Fix**: Make the relation bidirectional with `mappedBy = "parent"` on the parent side, or add `@JoinColumn(name = "parent_id")` on the unidirectional one-to-many to skip the join table.

## R03 — Bidirectional association without `addX` / `removeX` sync helpers

- **Severity**: HIGH
- **Detect**: An `@Entity` with a `@OneToMany(mappedBy = ...)` collection field, but no method that updates both sides (no `child.setParent(this)` line in any setter or helper on the parent).
- **Why**: Persisting the parent doesn't see new children unless cascade is set AND both sides are wired. Removing a child without clearing its back-reference leaves a dangling FK. Dirty-checking misses the changes.
- **Source**: `the-best-way-to-synchronize-bidirectional-entity-associations-with-jpa-and-hibernate.md`, `cascade-and-associations.md`

## R04 — `List` used for a `@ManyToMany` association

- **Severity**: MEDIUM
- **Detect**: `private List<Tag> tags` (or any other collection of a non-owning entity) annotated with `@ManyToMany`.
- **Why**: Hibernate translates a `List<T>` update on `@ManyToMany` into "DELETE all rows from join table, then INSERT all new rows" (the famous "bag" recreation behavior). With `Set<T>` it correctly diffs.
- **Source**: `the-best-way-to-map-a-manytomany-association-with-extra-columns-with-jpa-and-hibernate.md`

## R05 — `equals` / `hashCode` on `@Entity` using mutable fields or auto-generated `@Id`

- **Severity**: HIGH
- **Detect**: An `@Entity` overrides `equals` and `hashCode` and references the `@Id` field (auto-generated) or any non-final, non-business-key field.
- **Why**: Before `persist()`, `id` is `null`; after, it changes. Storing the entity in a `HashSet` before `persist()` then mutating fields breaks `contains()`. Cascade with `Set` collections silently drops entities.
- **Source**: `the-best-way-to-implement-equals-hashcode-and-tostring-with-jpa-and-hibernate.md`
- **Fix**: Use a business / natural key, or use a UUID assigned in the constructor.

## R06 — `@OneToMany` or `@ManyToMany` with `fetch = FetchType.EAGER`

- **Severity**: HIGH
- **Detect**: `@OneToMany` or `@ManyToMany` annotation containing `fetch = FetchType.EAGER` (or `fetch=EAGER` after import).
- **Why**: Every read of the parent loads the entire collection, regardless of whether the caller needs it. With two eager collections, Hibernate throws `MultipleBagFetchException` or produces a Cartesian product. Pagination is silently broken.
- **Source**: `eager-fetching-is-a-code-smell.md`, `hibernate-facts-the-importance-of-fetch-strategy.md`

## R07 — `@ManyToOne` / `@OneToOne` without explicit `fetch = FetchType.LAZY`

- **Severity**: MEDIUM
- **Detect**: `@ManyToOne` or `@OneToOne` annotation present, with no `fetch =` attribute.
- **Why**: JPA defaults `@ManyToOne` and `@OneToOne` to `EAGER`. Every entity load runs a JOIN even when the caller never touches the association. For `@OneToOne`, the non-owning side is *always* eager unless you use `@MapsId` or bytecode enhancement.
- **Source**: `the-best-way-to-map-a-onetoone-relationship-with-jpa-and-hibernate.md`, `fetching-and-n-plus-one.md`
- **Note**: MEDIUM, not HIGH — sometimes you legitimately need the association. But explicit `LAZY` should be the default and EAGER should be documented with a reason.

## R08 — `@LazyCollection(LazyCollectionOption.EXTRA)`

- **Severity**: HIGH
- **Detect**: `@LazyCollection(LazyCollectionOption.EXTRA)` anywhere.
- **Why**: Each access (`collection.size()`, `collection.contains(x)`) fires a fresh SQL query. Looks lazy, behaves N+1.
- **Source**: `the-best-way-to-map-an-onetomany-association-with-extra-lazy-collections.md`

## R09 — JPQL `JOIN FETCH` of two unrelated `*ToMany` collections

- **Severity**: CRITICAL
- **Detect**: A JPQL/HQL string containing two `JOIN FETCH` clauses where both target collections (`*ToMany`) of the root entity.
- **Why**: Produces a Cartesian product. A `Post` with 10 `comments` and 5 `tags` returns 50 rows; Hibernate hydrates duplicates. Even one such query under load can saturate the DB.
- **Source**: `hibernate-multiplebagfetchexception.md`, `the-best-way-to-fix-the-multiplebagfetchexception.md`
- **Fix**: Run two queries (parent → comments, parent → tags) and merge in-memory, or use `Set` + secondary lookup.

## R10 — `Pageable` combined with `JOIN FETCH` on a `*ToMany`

- **Severity**: HIGH
- **Detect**: A Spring Data repository method or `TypedQuery` that takes a `Pageable` parameter AND has `JOIN FETCH` on a `*ToMany` association in its JPQL.
- **Why**: Hibernate logs `HHH000104: firstResult/maxResults specified with collection fetch; applying in memory!` and loads the entire result set into memory, then slices. Defeats pagination entirely.
- **Source**: `hibernate-hhh000104-entity-fetch-pagination-warning-message.md`, `fetching-and-n-plus-one.md`
- **Fix**: Two-step. Page parent IDs first (keyset preferred), then `JOIN FETCH` children `WHERE parent.id IN :ids`.

## R11 — `GenerationType.IDENTITY` in a class participating in bulk inserts

- **Severity**: HIGH
- **Detect**: `@GeneratedValue(strategy = GenerationType.IDENTITY)` on an entity that is created in a loop / `saveAll` / batch context. Also: any IDENTITY entity on Postgres/Oracle (where SEQUENCE is the better default).
- **Why**: IDENTITY disables JDBC batching entirely. Hibernate must execute each INSERT and read back the generated key. A 10 000-row insert becomes 10 000 round-trips.
- **Source**: `why-you-should-never-use-the-table-identifier-generator-with-jpa-and-hibernate.md`, `the-best-way-to-do-batch-processing-with-jpa-and-hibernate.md`
- **Fix**: Switch to `SEQUENCE` with a pooled-lo optimizer.

## R12 — `@GeneratedValue` without explicit `strategy` on Postgres/Oracle

- **Severity**: MEDIUM
- **Detect**: `@GeneratedValue` with no `strategy =` attribute, in a project whose `application.properties` shows `postgresql` or `oracle` dialect.
- **Why**: Defaults to `AUTO`, which Hibernate interprets differently across versions (older versions → `TABLE` on dialects without explicit support; newer → SEQUENCE). Reproducibility suffers; perf is suboptimal.
- **Source**: `hibernate-default-entity-sequence.md`

## R13 — Loop with `persist()` / `save()` and no `flush()/clear()` cadence

- **Severity**: HIGH
- **Detect**: A `for` / `while` loop containing `entityManager.persist(...)`, `session.persist(...)`, or `repository.save(...)` with more than ~5 iterations, and **no** call to `entityManager.flush(); entityManager.clear();` or `repository.flush()` inside the loop.
- **Why**: All N entities accumulate in the persistence context. Dirty-check cost grows O(N²). Heap pressure increases until OOM. JDBC batches never flush.
- **Source**: `the-best-way-to-do-batch-processing-with-jpa-and-hibernate.md`, `how-to-batch-insert-and-update-statements-with-hibernate.md`
- **Fix**: Flush & clear every `hibernate.jdbc.batch_size` iterations, or use `StatelessSession`, or `saveAll(batch)`.

## R14 — Entity-by-entity delete loop instead of bulk `@Modifying @Query`

- **Severity**: MEDIUM
- **Detect**: Loop with `entityManager.remove(entity)` or `repository.delete(entity)` for many rows, where the delete criterion could be a single JPQL `DELETE FROM Foo WHERE ...`.
- **Why**: N SELECT (load entity) + N DELETE + dirty-check overhead. Bulk JPQL or `CriteriaDelete` is 1 round-trip.
- **Source**: `how-to-batch-delete-statements-with-hibernate.md`

## R15 — Mutable entity used across HTTP requests with no `@Version`

- **Severity**: MEDIUM
- **Detect**: `@Entity` class that is read by one controller action and written by another (typical CRUD pattern), and **no** `@Version` field.
- **Why**: Two clients editing the same row → silent lost update. The second `merge()` overwrites the first with no exception.
- **Source**: `an-entity-modeling-strategy-for-scaling-optimistic-locking.md`, `optimistic-locking-version-property-jpa-hibernate.md`
- **Fix**: Add `@Version private Long version;`. Handle `OptimisticLockException` with retry or 409.

## R16 — `find()` / `get()` in a write path without `LockModeType`

- **Severity**: MEDIUM
- **Detect**: `entityManager.find(Entity.class, id)` immediately followed by mutation + persist, with no `LockModeType` argument.
- **Why**: Race window between read and write. With optimistic locking the conflict is caught later; without `@Version`, it's silent.
- **Source**: `hibernate-locking-patterns-how-do-pessimistic_read-and-pessimistic_write-work.md`

## R17 — `@Transactional` on a query-only method without `readOnly = true`

- **Severity**: LOW
- **Detect**: `@Transactional` annotation with no `readOnly = true`, on a method whose body executes only queries (no `persist` / `merge` / `remove` / `@Modifying`).
- **Why**: Hibernate skips storing pre-update entity snapshots when `readOnly=true` (≈50% less heap per loaded entity). Spring also routes to read replicas via `AbstractRoutingDataSource`. Both lost otherwise.
- **Source**: `spring-read-only-transaction-hibernate-optimization.md`, `read-write-read-only-transaction-routing-spring.md`

## R18 — Self-invocation of `@Transactional` method (proxy bypass)

- **Severity**: HIGH
- **Detect**: Inside a Spring bean, a method calls another `@Transactional` method on `this` (e.g., `this.doInNewTx()`), expecting a new transaction.
- **Why**: Spring's transaction advice runs on the proxy. Calling `this.method()` bypasses the proxy → no transaction. The annotation silently does nothing.
- **Source**: `spring-transaction-best-practices.md`
- **Fix**: Inject the bean into itself, or split into two beans, or call via `AopContext.currentProxy()`.

## R19 — `spring.jpa.open-in-view = true` (Spring Boot default)

- **Severity**: HIGH
- **Detect**: `spring.jpa.open-in-view=true` in `application.properties`/`application.yml`, OR no `spring.jpa.open-in-view` setting (default is `true`).
- **Why**: Keeps the persistence context open during view rendering. Encourages N+1 queries triggered by Jackson serializing lazy associations. Holds a DB connection across the entire HTTP request including any external service calls.
- **Source**: `the-open-session-in-view-anti-pattern.md`
- **Fix**: `spring.jpa.open-in-view=false`. Fetch what you need in the service layer using `@EntityGraph` or DTO projections.

## R20 — `hibernate.enable_lazy_load_no_trans = true`

- **Severity**: HIGH
- **Detect**: `hibernate.enable_lazy_load_no_trans=true` anywhere in config.
- **Why**: Allows lazy associations to load outside a transaction by opening a *temporary* new transaction per lazy access. Each lazy access = 1 new tx + 1 connection acquire/release. Catastrophic under load.
- **Source**: `the-hibernate-enable_lazy_load_no_trans-anti-pattern.md`
- **Fix**: Same as R19 — fetch eagerly in the service layer.

---

## Reviewer guidance

- Always quote the exact code with file path + line number.
- For MEDIUM rules, frame as a question to the author ("Was EAGER intentional here?") rather than a demand.
- If `references/critical-rules.md` and the actual code disagree (e.g., a "WRONG" pattern is the right call in a specific narrow context), trust the code if the author has explained the why in a comment or commit message — but still flag it for the human reviewer's awareness.
