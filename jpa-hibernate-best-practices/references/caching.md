# Caching in JPA/Hibernate

## Core Principles

- Treat the first-level cache (Persistence Context) as a mandatory, per-`Session`/`EntityManager`, thread-bound `Map<EntityUniqueKey, Object>`; it guarantees one managed reference per `(entityName, id)` and provides application-level repeatable reads. It is NOT a sharable cache and does not survive transaction boundaries. [jpa-hibernate-first-level-cache.md]
- The first-level cache is a transactional write-behind cache: `persist`, `merge`, `remove` only enqueue state transitions; SQL is emitted at `flush` time, allowing JDBC batching. Do not assume DML executes when the API call returns. [jpa-hibernate-first-level-cache.md, a-beginners-guide-to-cache-synchronization-strategies.md]
- The second-level cache (2LC) is bound to the `SessionFactory`, shared across sessions, and stores the entity *loaded state* (`Object[]`) — not the managed entity reference. Each session rebuilds its own entity from that array. [jpa-hibernate-second-level-cache.md, how-does-hibernate-store-second-level-cache-entries.md]
- Enable 2LC only when (a) primary-node read traffic is the bottleneck, (b) data is read-mostly, and (c) entities are accessed by primary key — 2LC lookups happen via `EntityManager.find`/`getReference`, not by arbitrary JPQL `where` clauses. JPQL/HQL by id still hits 2LC, but a `select ... where title = ?` does not. [jpa-hibernate-second-level-cache.md]
- Setting `hibernate.cache.use_second_level_cache=true` alone does nothing — the default `NoCachingRegionFactory` silently discards puts. You MUST configure `hibernate.cache.region.factory_class` (Ehcache, Infinispan, JCache, etc.). [how-does-hibernate-store-second-level-cache-entries.md]
- Pick the `CacheConcurrencyStrategy` per entity based on mutability and consistency budget: `READ_ONLY` for immutable reference data; `NONSTRICT_READ_WRITE` for rarely-modified data where short staleness is acceptable; `READ_WRITE` for read-write data needing strong-ish consistency via soft locks; `TRANSACTIONAL` only with a JTA manager (XA two-phase commit) when cache and DB must be one atomic unit. [how-does-hibernate-read_only-cacheconcurrencystrategy-work.md, how-does-hibernate-nonstrict_read_write-cacheconcurrencystrategy-work.md, how-does-hibernate-read_write-cacheconcurrencystrategy-work.md, how-does-hibernate-transactional-cacheconcurrencystrategy-work.md]
- The query cache (`hibernate.cache.use_query_cache=true` + `setCacheable(true)`) is read-through and stores `{query, params} -> List<ID>`; it does NOT store entities. Entity hydration still goes through 2LC. Without 2LC enabled for the target entities, the query cache produces an N+1 (one SELECT per id). [how-does-hibernate-query-cache-work.md, hibernate-query-cache-n-plus-1-issue.md]
- The query cache invalidates on ANY write to the referenced tables — even unrelated rows. On write-heavy tables this gives a near-zero hit ratio; reserve it for read-mostly tables. [how-does-hibernate-query-cache-work.md, hibernate-query-cache-n-plus-1-issue.md]
- Collection cache (`@Cache` on `@OneToMany`/`@ManyToMany`/`@ElementCollection`) stores only the child entity *identifiers*; the children themselves must also be `@Cacheable` and `@Cache`-annotated or every collection access re-hydrates from DB. [how-does-hibernate-collection-cache-work.md]
- Cache region names default to the fully-qualified entity/collection name (e.g. `com.acme.Post`, `com.acme.Post.comments`). Use explicit `@Cache(region="...")` so eviction, sizing, and TTL can be controlled per region in your provider config. [how-does-hibernate-store-second-level-cache-entries.md]
- Distinguish the JPA/Hibernate data caches above from the Hibernate `QueryPlanCache` (parsed JPQL/HQL AST + native query metadata, sized via `hibernate.query.plan_cache_max_size`, default 2048) and the JDBC driver statement cache (`cachePrepStmts`/`useServerPrepStmts` on MySQL, `preparedStatementCacheQueries`/`preparedStatementCacheSizeMiB` on PostgreSQL, plus `prepareThreshold=5`). These are separate, non-overlapping caches; tune each. [hibernate-query-plan-cache.md, mysql-jdbc-statement-caching.md, postgresql-jdbc-statement-caching.md]
- A `java.util.Map` (or `ConcurrentHashMap`) is NOT a cache — it has no eviction policy, no size bound, no statistics, no weak references, no persistence. Use a real provider (Ehcache, Caffeine, Hazelcast, Redis, Infinispan) before reaching for ad hoc memoization. [caching-best-practices.md]
- 2LC duplicates the system of record; for the same reason XA, write-through, or strict invalidation semantics matter, accept that any cache introduces a window of possible drift unless you specifically chose `TRANSACTIONAL` + XA. Plan for it; do not pretend it isn't there. [things-to-consider-before-jumping-to-enterprise-caching.md, a-beginners-guide-to-cache-synchronization-strategies.md]

## Decision Trees

### Tree 1: Do I need a cache at all?

```
Is the slow path a Hibernate-generated SQL statement?
├── Yes → Fix the SQL FIRST. Cache is the LAST resort.
│   ├── N+1? → JOIN FETCH / @EntityGraph / batch fetching. [caching-best-practices.md]
│   ├── Bad fetch plan? → Switch to LAZY, project DTOs.
│   ├── Missing index? → Add it. Indexes must fit RAM.
│   └── Still slow after tuning? → Continue below.
├── No (external API, expensive in-JVM computation) →
│   └── Use Spring @Cacheable / Caffeine / Guava LoadingCache.
│       Do NOT enable Hibernate 2LC for this.

Now: are reads on the database PRIMARY node the bottleneck?
├── No, replicas can absorb it → Add read replicas. Skip 2LC.
├── Yes, hot keys lookups by PK dominate → Enable 2LC + entity @Cache.
└── Yes, but the hot paths are arbitrary queries → 2LC alone won't help.
    Consider DTO projections + query cache OR a materialized view OR
    an application-level Redis cache populated by CDC.
    [things-to-consider-before-jumping-to-enterprise-caching.md,
     jpa-hibernate-second-level-cache.md,
     cache-synchronization-jooq-postgresql-functions.md]
```

### Tree 2: Which `CacheConcurrencyStrategy` do I pick?

```
Will the entity EVER change after insert?
├── No (country codes, currencies, lookup tables, immutable audit) →
│   READ_ONLY  -- cheapest, no locking, modifications throw. [how-does-hibernate-read_only-cacheconcurrencystrategy-work.md]
│
├── Yes →
│   Is the data financial/inventory/anything where a single stale
│   read causes business damage?
│   ├── Yes →
│   │   Do you have a JTA transaction manager + XA-capable cache
│   │   (Ehcache xa_strict, Infinispan transactional)?
│   │   ├── Yes → TRANSACTIONAL (2PC, cache+DB atomic). [how-does-hibernate-transactional-cacheconcurrencystrategy-work.md]
│   │   └── No  → READ_WRITE (async write-through with soft locks).
│   │             Combine with @Version optimistic locking. [how-does-hibernate-read_write-cacheconcurrencystrategy-work.md]
│   └── No (catalog descriptions, user profiles, blog posts) →
│       NONSTRICT_READ_WRITE -- cheap, invalidates twice (pre/post
│       commit) but has a small drift window. Add @Version anyway. [how-does-hibernate-nonstrict_read_write-cacheconcurrencystrategy-work.md]
```

### Tree 3: Should I enable the query cache?

```
Is 2LC ALREADY enabled for every entity returned by the query?
├── No → STOP. Enabling query cache without 2LC creates N+1: one
│         SELECT per cached identifier. [hibernate-query-cache-n-plus-1-issue.md]
├── Yes →
│   Are the queried tables read-mostly (write rate ≪ read rate)?
│   ├── No → Don't cache. ANY write invalidates the query region. [how-does-hibernate-query-cache-work.md]
│   ├── Yes →
│       Are the queries parameterized by a small bounded key space
│       (e.g. top-10 lists, by-tag, by-category)?
│       ├── No (unbounded param permutations) → Cache fills with
│       │     cold entries that never hit again. Don't.
│       └── Yes → setCacheable(true). Also consider caching the
│             DTO projection itself rather than entity ids. [hibernate-query-cache-dto-projection.md]
```

### Tree 4: Cache miss returns null — should I cache the "not found"?

```
Is the lookup by stable natural id (ISBN, SKU, email) that clients
may probe repeatedly?
├── No → Don't bother; ordinary 2LC suffices.
├── Yes →
│   Configure `hibernate.cache.use_reference_entries=true` and let
│   the 2LC store null markers, OR wrap the lookup with a Caffeine
│   cache holding Optional<Entity>. Otherwise every miss hits the DB.
│   [jpa-hibernate-cache-non-existing-entity-fetch-results.md]
```

### Tree 5: Statement/plan cache tuning — where does my slowdown live?

```
Profile shows time in JPQL parsing (CompositeQueryParser/HqlSqlWalker)?
├── Yes → bump hibernate.query.plan_cache_max_size (default 2048
│         is too small for apps with many distinct queries).
│         Also: stop generating dynamic JPQL strings per request. [hibernate-query-plan-cache.md]
└── No  →
    Profile shows time in driver-side prepare / server parse?
    ├── MySQL → useServerPrepStmts=true; cachePrepStmts=true;
    │   prepStmtCacheSize=500; prepStmtCacheSqlLimit=2048. [mysql-jdbc-statement-caching.md]
    ├── PostgreSQL → prepareThreshold=5 (default) is fine; raise
    │   preparedStatementCacheQueries (default 256). [postgresql-jdbc-statement-caching.md]
    └── IN-clause causing N distinct execution plans?
        → hibernate.query.in_clause_parameter_padding=true. [improve-statement-caching-efficiency-in-clause-parameter-padding.md]
```

## Anti-patterns: WRONG / CORRECT

### 1. Enabling query cache without 2LC for the queried entities

WRONG — query cache stores ids; missing entity 2LC produces N+1.

```java
// persistence.xml: use_query_cache=true, use_second_level_cache=true
// BUT entity has no @Cache annotation

@Entity
@Table(name = "post")
public class Post {
    @Id @GeneratedValue Long id;
    String title;
    // no @org.hibernate.annotations.Cache
}

List<Post> posts = entityManager.createQuery(
        "select p from Post p", Post.class)
    .setHint("org.hibernate.cacheable", true)
    .getResultList();
// First call: 1 query.
// Second call: query cache hit returns [1,2,...,N], then N SELECTs
// to hydrate each Post — silent N+1.
```

CORRECT — annotate the entity, then enable query cache.

```java
@Entity
@Table(name = "post")
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Post {
    @Id @GeneratedValue Long id;
    String title;
}

List<Post> posts = entityManager.createQuery(
        "select p from Post p", Post.class)
    .setHint("org.hibernate.cacheable", true)
    .getResultList();
// Second call: 1 query-cache hit + N 2LC hits, zero SQL.
```
Source: [hibernate-query-cache-n-plus-1-issue.md]

### 2. Caching an entity that has a `@OneToMany` without caching the collection

WRONG — the parent loads from 2LC, but every access to `getComments()` re-executes a SELECT.

```java
@Entity
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Post {
    @Id Long id;

    @OneToMany(mappedBy = "post", fetch = FetchType.LAZY)
    private List<PostComment> comments = new ArrayList<>();
    // missing @Cache on the association
}
```

CORRECT — collection cache stores child ids; children must also be cacheable.

```java
@Entity
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Post {
    @Id Long id;

    @OneToMany(mappedBy = "post", fetch = FetchType.LAZY)
    @org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
    private List<PostComment> comments = new ArrayList<>();
}

@Entity
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class PostComment {
    @Id Long id;
    @ManyToOne(fetch = FetchType.LAZY) Post post;
    String review;
}
```
Source: [how-does-hibernate-collection-cache-work.md]

### 3. Mutating an entity cached as `READ_ONLY`

WRONG — `READ_ONLY` discards loaded state; updates throw `HibernateException`.

```java
@Entity
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_ONLY)
public class Repository {
    @Id Long id;
    String name;
}

// Later, in a transaction:
Repository r = em.find(Repository.class, 1L);
r.setName("Renamed");          // dirty-checking will fire UPDATE
em.flush();                    // throws: Can't write to read-only entity
```

CORRECT — for mutable data choose `NONSTRICT_READ_WRITE` or `READ_WRITE`.

```java
@Entity
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Repository {
    @Id Long id;
    @Version Long version;
    String name;
}
```
Source: [how-does-hibernate-read_only-cacheconcurrencystrategy-work.md, how-does-hibernate-read_write-cacheconcurrencystrategy-work.md]

### 4. Using `NONSTRICT_READ_WRITE` for inventory / balance / financial entities

WRONG — `NONSTRICT_READ_WRITE` invalidates *around* commit (before flush and after), but a concurrent reader between those two invalidations can repopulate the cache with the OLD value. A second reader then sees stale stock.

```java
@Entity
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.NONSTRICT_READ_WRITE)
public class InventoryItem {
    @Id Long id;
    int availableUnits;        // money/stock — drift = real $$$
}

// Two threads decrement availableUnits ⇒ one read may show pre-decrement value.
```

CORRECT — `READ_WRITE` (soft-lock window prevents repopulation with stale value) or `TRANSACTIONAL` under JTA. Always pair with `@Version`.

```java
@Entity
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class InventoryItem {
    @Id Long id;
    @Version Long version;
    int availableUnits;
}
```
Source: [how-does-hibernate-nonstrict_read_write-cacheconcurrencystrategy-work.md, how-does-hibernate-read_write-cacheconcurrencystrategy-work.md, how-does-hibernate-transactional-cacheconcurrencystrategy-work.md]

### 5. Exposing the live cached collection / using a mutable embeddable

WRONG — 2LC stores references that the application then mutates outside Hibernate's awareness.

```java
@Entity
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Commit {
    @Id Long id;

    @ElementCollection
    @org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
    private List<Change> changes = new ArrayList<>();

    public List<Change> getChanges() { return changes; }  // leaked
}

// Caller does:
commit.getChanges().add(new Change("a.txt", "diff"));    // bypasses dirty-checking guarantees
// other Sessions read stale collection from 2LC
```

CORRECT — return a defensive copy and route mutations through an addX/removeX method; treat embeddables as immutable.

```java
public List<Change> getChanges() {
    return Collections.unmodifiableList(changes);
}
public void addChange(Change c) { changes.add(c); }
public void removeChange(Change c) { changes.remove(c); }
```
Source: [how-does-hibernate-collection-cache-work.md, caching-best-practices.md]

### 6. Building a homemade cache from a `HashMap`

WRONG — no bounds, no eviction, no statistics, ever-growing heap.

```java
public class PostService {
    private final Map<Long, Post> cache = new ConcurrentHashMap<>();

    public Post find(Long id) {
        return cache.computeIfAbsent(id, k -> repository.findById(k).orElse(null));
    }
}
// Eventually OOMs; cache never reflects DB updates from peers.
```

CORRECT — use a real cache with size limits, TTL, and a coherent invalidation strategy.

```java
private final LoadingCache<Long, Post> cache = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofMinutes(10))
    .recordStats()
    .build(id -> repository.findById(id).orElse(null));
```
Source: [caching-best-practices.md]

### 7. Cache stampede after `evictAll`

WRONG — manual full eviction during peak load lets every node refetch concurrently.

```java
sessionFactory.getCache().evictAllRegions();   // 9am marketing flush
// 200 nodes × 50 requests = 10 000 simultaneous SELECT bursts
```

CORRECT — evict per region, stagger, or use the cache provider's lazy refresh; assume `evictAll` is NOT coherent across distributed nodes anyway. Prefer targeted `evictEntity(Post.class, id)` driven by domain events / CDC.

```java
Cache cache = sessionFactory.getCache();
cache.evictEntityData(Post.class, postId);
cache.evictCollectionData(Post.class.getName() + ".comments", postId);
// Or use CDC (e.g. PostgreSQL function + jOOQ poll) to push deltas:
// see cache-synchronization-jooq-postgresql-functions.md
```
Source: [cache-synchronization-jooq-postgresql-functions.md, things-to-consider-before-jumping-to-enterprise-caching.md]

### 8. Caching write-heavy entities

WRONG — `@Cache` on entities that are updated more often than read just adds invalidation overhead.

```java
@Entity
@org.hibernate.annotations.Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class AuditEvent {           // 5000 inserts/sec, never re-read
    @Id Long id;
    Instant occurredAt;
    String payload;
}
```

CORRECT — leave write-heavy / append-only entities OUT of 2LC; let them go straight to the DB.

```java
@Entity
public class AuditEvent {           // no @Cache
    @Id Long id;
    Instant occurredAt;
    String payload;
}
```
Source: [things-to-consider-before-jumping-to-enterprise-caching.md, caching-best-practices.md]

### 9. Dynamic IN-clause polluting both the plan cache and the statement cache

WRONG — every distinct list size produces a new SQL string, new plan, new statement-cache entry.

```java
em.createQuery(
    "select p from Post p where p.id in :ids", Post.class)
  .setParameter("ids", idList)        // sometimes 3 ids, sometimes 17, sometimes 42
  .getResultList();
// 40 different SQL signatures ⇒ plan cache and JDBC stmt cache thrashing
```

CORRECT — enable IN-list padding so sizes round up to powers of two.

```properties
hibernate.query.in_clause_parameter_padding=true
```

```java
em.createQuery(
    "select p from Post p where p.id in :ids", Post.class)
  .setParameter("ids", idList)
  .getResultList();
// Sizes are padded to 1, 2, 4, 8, 16, 32 ... → at most ~log2(N) signatures.
```
Source: [improve-statement-caching-efficiency-in-clause-parameter-padding.md]

### 10. Forgetting to enable the JDBC driver statement cache on MySQL

WRONG — MySQL JDBC defaults to client-side `PreparedStatement` *without* caching `ParseInfo`, so every prepare re-parses the SQL.

```properties
jdbc.url=jdbc:mysql://db:3306/app
# no useServerPrepStmts, no cachePrepStmts
```

CORRECT — turn on server-side prepare + statement cache. Sizes must accommodate the working set.

```properties
jdbc.url=jdbc:mysql://db:3306/app?\
useServerPrepStmts=true&\
cachePrepStmts=true&\
prepStmtCacheSize=500&\
prepStmtCacheSqlLimit=2048
```
PostgreSQL equivalent — defaults are mostly OK, but raise the cache for query-heavy apps:

```properties
jdbc.url=jdbc:postgresql://db:5432/app?\
prepareThreshold=5&\
preparedStatementCacheQueries=512&\
preparedStatementCacheSizeMiB=10
```
Source: [mysql-jdbc-statement-caching.md, postgresql-jdbc-statement-caching.md]

## Performance Pitfalls

- 2LC is a `(loadedState[], version)` store, not an object cache: every cache hit re-materializes the entity. Memory and CPU savings exist but are smaller than developers expect — measure hit ratio AND mean load time before/after. [jpa-hibernate-second-level-cache.md, how-does-hibernate-store-second-level-cache-entries.md]
- Query cache regions are invalidated on table updates with table-level granularity. One stray write to the `post` table invalidates every cached `select ... from Post ...` query, even queries that did not touch the modified row. Reserve the query cache for read-mostly tables with bounded query parameter spaces. [how-does-hibernate-query-cache-work.md, hibernate-query-cache-n-plus-1-issue.md]
- The `hibernate.query.plan_cache_max_size` default (2048) is fine for small apps but causes constant LRU churn in apps that compile thousands of distinct JPQL strings. Symptoms: high CPU in `QueryTranslatorImpl`, GC pressure from short-lived AST objects. Raise it or — better — stop generating dynamic JPQL per request. [hibernate-query-plan-cache.md]
- Skewed data + PostgreSQL generic plans: after 5 executions PostgreSQL switches a `PreparedStatement` to a generic plan, which can pick the wrong index on selective values. Override per query with `SET LOCAL plan_cache_mode = 'force_custom_plan'` or pass it as a connection hint. [postgresql-plan-cache-mode.md]
- 2LC under a non-distributed provider (per-JVM Ehcache) gives each app instance its own cache: scaling out multiplies the cold-cache problem and creates per-node drift. Use a distributed/replicated provider (Redis, Hazelcast, Infinispan clustered) for >1 node deployments. [jpa-hibernate-second-level-cache.md, things-to-consider-before-jumping-to-enterprise-caching.md]
- The first-level cache holds both the managed entity AND its loaded-state snapshot for dirty checking, so long-running sessions / batch jobs balloon in heap. Run batches in `FlushMode.MANUAL`, periodically `em.flush(); em.clear();`, or load read-only entities (`org.hibernate.readOnly` hint / `@Transactional(readOnly=true)` since Spring 5.1) so the loaded state is discarded. [jpa-hibernate-first-level-cache.md, jpa-hibernate-second-level-cache.md]
- JPQL/HQL queries that select entities by non-id predicates bypass 2LC: even if every `Post` is cached, `select p from Post p where p.title = ?1` still executes the SQL. The result rows are then *reconciled* against 2LC, but the DB hit is unavoidable unless the query itself is cached. [jpa-hibernate-second-level-cache.md, how-does-hibernate-query-cache-work.md]
- Caching `null` ("entity-does-not-exist") is NOT automatic. Repeated probes for non-existent ids hammer the DB. Either enable provider-side null caching or wrap the lookup in an application cache that stores `Optional`. [jpa-hibernate-cache-non-existing-entity-fetch-results.md]
- Cache abstractions hide costs. `@Cacheable` on a method that already returns in <1 ms adds (de)serialization + key computation + lock overhead and slows the call. Profile before and after — the Spring cache infrastructure itself has measurable overhead. [caching-best-practices.md]
- Mixing application-level Spring `@Cacheable` with Hibernate 2LC over the same entity yields two divergent caches with two independent invalidation timelines. Pick one layer per entity. [caching-best-practices.md, things-to-consider-before-jumping-to-enterprise-caching.md]
- Cache stampedes after deploys / restarts: each new node starts cold and floods the DB. Mitigate with cache warmup, request coalescing (e.g. Caffeine's `LoadingCache` collapses concurrent loads for the same key), distributed 2LC, or CDC-driven prepopulation. [things-to-consider-before-jumping-to-enterprise-caching.md, cache-synchronization-jooq-postgresql-functions.md]

## Citations

- `[a-beginners-guide-to-cache-synchronization-strategies.md]` — Cache-aside, read-through, write-through, write-behind synchronization patterns and where Hibernate fits.
- `[cache-synchronization-jooq-postgresql-functions.md]` — Using PostgreSQL functions + jOOQ + CDC to push consistent deltas into an application-level Redis cache.
- `[caching-best-practices.md]` — Five rules: Map ≠ Cache, use an abstraction, beware overhead, cache is the last resort, consistency matters.
- `[hibernate-query-cache-dto-projection.md]` — Storing DTO projections (not entities) in the second-level query cache to serve summary pages cheaply.
- `[hibernate-query-cache-n-plus-1-issue.md]` — How query cache without 2LC silently degenerates into N+1; correct configuration.
- `[hibernate-query-plan-cache.md]` — JPQL/HQL AST plan cache, native query parameter metadata cache, `hibernate.query.plan_cache_max_size` defaults.
- `[how-does-hibernate-collection-cache-work.md]` — Collection cache stores child identifiers; children must be `@Cache`-annotated.
- `[how-does-hibernate-nonstrict_read_write-cacheconcurrencystrategy-work.md]` — Double invalidation (pre- and post-commit), drift window, inconsistency demo.
- `[how-does-hibernate-query-cache-work.md]` — Enable flags, read-through semantics, table-level invalidation of cached query regions.
- `[how-does-hibernate-read_only-cacheconcurrencystrategy-work.md]` — Cheapest strategy for immutable data; mutation attempts throw.
- `[how-does-hibernate-read_write-cacheconcurrencystrategy-work.md]` — Asynchronous write-through with soft locks; cache populated after commit for SEQUENCE-id entities.
- `[how-does-hibernate-store-second-level-cache-entries.md]` — `RegionFactory` requirement, `NoCachingRegionFactory` pitfall, entity loading flow.
- `[how-does-hibernate-transactional-cacheconcurrencystrategy-work.md]` — JTA/XA enlistment, `xa_strict` vs `xa` modes, durability vs latency trade-off.
- `[improve-statement-caching-efficiency-in-clause-parameter-padding.md]` — `hibernate.query.in_clause_parameter_padding` rounds IN-list sizes to powers of two.
- `[jpa-hibernate-cache-non-existing-entity-fetch-results.md]` — Caching null results so repeated lookups for missing ids do not hit the DB.
- `[jpa-hibernate-first-level-cache.md]` — Persistence Context internals, write-behind, application-level repeatable reads, loaded state.
- `[jpa-hibernate-second-level-cache.md]` — SessionFactory-scoped cache, what gets cached (entity loaded state, collection ids, query results, natural-id mapping), distributed deployment.
- `[mysql-jdbc-statement-caching.md]` — `useServerPrepStmts`, `cachePrepStmts`, `ParseInfo` cache, server vs client prepared statements.
- `[postgresql-jdbc-statement-caching.md]` — `prepareThreshold`, `preparedStatementCacheQueries`, `preparedStatementCacheSizeMiB`.
- `[postgresql-plan-cache-mode.md]` — `plan_cache_mode = force_custom_plan` for queries over skewed data distributions.
- `[speedment-orm-deliberate-enterprise-caching.md]` — Deliberate (not accidental) cache architecture; Speedment + Hazelcast in-memory data grid model.
- `[things-to-consider-before-jumping-to-enterprise-caching.md]` — Cache as a duplicated system of record; XA, distributed cache topology, drift, eventual consistency.
