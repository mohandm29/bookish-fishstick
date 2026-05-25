# SQL and Query Performance

## Core Principles

- Inspect the execution plan before tuning. SQL is declarative — the database decides _how_ to fetch via a Cost-Based Optimizer that may choose sequential scan, index scan, nested loops, hash join, or merge join. Without the actual plan you are guessing [`sql-execution-plan-oracle.md`, `execution-plan-sql-server.md`, `relational-database-sql-prepared-statements` linked from `index-selectivity.md`].
- Index every column that appears in WHERE, JOIN, ORDER BY, and GROUP BY — but only if the column is _selective_. The optimizer will skip an index whose predicate matches a large fraction of rows (the PostgreSQL `DONE` example matched 95% of rows and triggered a Seq Scan instead of using `idx_task_status`) [`index-selectivity.md`].
- Prefer covering indexes (`INCLUDE` columns on SQL Server, INCLUDE on PostgreSQL, or wider B+Tree on MySQL) to avoid Bookmark Lookups back to the clustered/heap table [`clustered-index.md`, `postgresql-index-types.md`].
- Choose a compact, monotonically-increasing Primary Key (int/bigint, TSID) — clustered indexes (SQL Server, MySQL/InnoDB) embed the PK into every secondary index leaf, so a 16-byte random UUID amplifies storage and causes page splits [`clustered-index.md`].
- Replace OFFSET pagination with keyset (seek-method) pagination once tables grow large. OFFSET scans every skipped row; keyset uses an indexed `(sort_col, id) < (?, ?)` predicate and reads exactly one page [`sql-seek-keyset-pagination.md`, `keyset-pagination-jpa-hibernate.md`, `pagination-best-practices.md`].
- Limit every result set. Cap pages, add `setMaxResults`, and provide better filters instead of letting users paginate through millions of records — a single unbounded query can consume all DB CPU/IO [`pagination-best-practices.md`, `sql-query-limit-top-n-rows.md`].
- Prefer EXISTS (SemiJoin) over INNER JOIN + DISTINCT when the projection comes from one table only. EXISTS short-circuits at the first match per outer row [`sql-exists.md`, `exists-subqueries-jpa-hibernate.md`].
- Use PreparedStatements with bind parameters — never concatenate user input. String concatenation enables SQL injection on SQL Server, PostgreSQL, and MySQL (Oracle rejects multi-statement, but is still vulnerable to single-statement injection) [`a-beginners-guide-to-sql-injection-and-how-you-should-prevent-it.md`].
- Reuse execution plans via prepared statements and IN-clause parameter padding. Each unique statement text creates a new plan cache entry; padding `IN (?, ?, ?)` into power-of-two buckets dramatically improves plan cache hit ratio on Oracle and SQL Server [`relational-database-sql-prepared-statements` linked from `index-selectivity.md`, `tips-oracle-jpa-hibernate.md`].
- Set a query timeout on every long-running query via `jakarta.persistence.query.timeout` (ms) or `org.hibernate.timeout` (seconds). Unbounded queries hold locks and connections [`query-timeout-jpa-hibernate.md`, `jpa-hibernate-query-hints.md`].
- Always set an ORDER BY on paginated queries — SQL guarantees no particular order without it, and Hibernate will emit `LIMIT/FETCH NEXT` translated for each dialect [`query-pagination-jpa-hibernate.md`].
- For two-step JOIN FETCH on Page<T>: first paginate parent IDs in a separate query, then `JOIN FETCH` collections only for those IDs. Doing `JOIN FETCH` on a `Page` query forces Hibernate to paginate the Cartesian result set in memory [`query-pagination-jpa-hibernate.md` discussion, `n-plus-1-query-problem.md`].
- Use `StatementInspector` (e.g., Hypersistence Utils `QueryStackTraceLogger`) to attribute SQL back to the originating Java code path when diagnosing N+1 or slow queries [`source-sql-query-hibernate.md`].
- Activate the slow query log (`hibernate.log_slow_query=25` ms, `org.hibernate.SQL_SLOW=INFO`) during development and in pre-prod load tests — but remember it will not catch N+1 because each individual query is fast [`hibernate-slow-query-log.md`, `n-plus-1-query-problem.md`].
- Use database-side `auto_explain` (PostgreSQL), `STATISTICS_LEVEL=ALL` (Oracle), or `STATISTICS IO, TIME, PROFILE ON` (SQL Server) to capture _actual_ execution plans from production. The estimated plan from `EXPLAIN` may differ from the cached generic plan that ran in production [`postgresql-auto-explain.md`, `sql-execution-plan-oracle.md`, `execution-plan-sql-server.md`].

## Decision Trees

### Which query API?

```
Need a static finder by ID / simple field?
  -> Spring Data JPA derived query OR EntityManager.find()
Need a dynamic WHERE (optional filters, sort, paging)?
  -> Criteria API or Blaze Persistence (avoid string concatenation)
Need a one-off projection, simple JPQL?
  -> JPQL via createQuery() + Tuple/DTO constructor expression
Need DB-specific feature (LATERAL, window function on Hibernate <6,
JSON_TABLE/OPENJSON, MERGE, recursive CTE, FOR UPDATE SKIP LOCKED)?
  -> createNativeQuery() (the EntityManager is a magic wand)
Need a hierarchical fetch?
  -> Hibernate 6 WITH RECURSIVE via JPQL, or native recursive CTE
[`source-sql-query-hibernate.md`, `hibernate-with-recursive-query.md`,
 `tips-oracle-jpa-hibernate.md`, `9-postgresql-high-performance-performance-tips.md`]
```

### Pagination?

```
Total rows <= a few thousand AND user navigates by page number?
  -> OFFSET pagination with setFirstResult/setMaxResults + COUNT(*)
Total rows large (>100k) AND user scrolls "next/previous" only?
  -> Keyset pagination: WHERE (sort_col, id) < (?, ?) ORDER BY ... LIMIT N
     [`sql-seek-keyset-pagination.md`, `keyset-pagination-jpa-hibernate.md`]
Need to stream a report or export?
  -> Java 8 Stream via Hibernate Query.stream(), but ALWAYS set fetch_size.
     PostgreSQL needs setHint(HINT_FETCH_SIZE, N); MySQL needs fetchSize
     Integer.MIN_VALUE or useCursorFetch=true; Oracle defaults to 10 so raise it.
     [`whats-new-in-jpa-2-2-stream-the-result-of-a-query-execution.md`,
      `9-high-performance-tips-when-using-mysql-with-jpa-and-hibernate.md`,
      `tips-oracle-jpa-hibernate.md`]
Need both pagination AND an entity collection?
  -> Two-step: page IDs first, then SELECT entity JOIN FETCH WHERE id IN (?)
     [`query-pagination-jpa-hibernate.md`]
```

### Join strategy?

```
Small driving set + indexed FK on the inner side?
  -> Nested Loops (DB picks automatically; O(n*m) bounded by index)
Both sides large + equality join + enough memory for hash table?
  -> Hash Join (PostgreSQL/SQL Server/Oracle; MySQL >=8.0.18)
Both sides large + sorted access via index OR ORDER BY matches join cols?
  -> Merge Join (Oracle/PostgreSQL/SQL Server; MySQL does not support)
Want to influence Oracle's plan from JPA/Hibernate?
  -> .addQueryHint("USE_NL(...)") or "LEADING(...)" via org.hibernate.query.Query
     [`execution-plan-oracle-hibernate-query-hints.md`]
Joining only to filter (projection contains no joined columns)?
  -> Rewrite as EXISTS subquery (SemiJoin), not INNER JOIN + DISTINCT
     [`exists-subqueries-jpa-hibernate.md`, `sql-exists.md`]
```

### Filter / index optimization?

```
Predicate touches all SELECT columns?
  -> Covering index (B+Tree with INCLUDE on PG/SQL Server, or composite on MySQL)
Predicate column has skewed data (e.g., status='DONE' = 95%)?
  -> Partial index: CREATE INDEX ... WHERE status <> 'DONE'
     [`index-selectivity.md`]
Predicate uses a function (LOWER, DATE_TRUNC, JSON extraction)?
  -> Expression index: CREATE INDEX ... ON t (LOWER(col))
     or CREATE INDEX ON book b (b.properties.title) on Oracle 19c
     [`tips-oracle-jpa-hibernate.md`]
Predicate is range + equality?
  -> Composite index ordered (equality_col, range_col)
Predicate is on a JSON attribute?
  -> PostgreSQL GIN index; SQL Server: computed column + index;
     Oracle: JSON_EXISTS BITMAP index for low-cardinality, B+Tree for high
     [`postgresql-index-types.md`, `tips-oracle-jpa-hibernate.md`,
      `sql-server-openjson.md`]
```

## Anti-patterns: WRONG / CORRECT

### 1. Offset pagination on large tables

```sql
-- WRONG: scans 5050 rows of the index to return 50
SELECT id FROM post
ORDER BY created_on DESC
OFFSET 5000 ROWS FETCH NEXT 50 ROWS ONLY;
```

```sql
-- CORRECT: keyset pagination scans 50 rows
SELECT id, created_on FROM post
WHERE (created_on, id) < ('2019-10-02 21:00:00.0', 4951)
ORDER BY created_on DESC, id DESC
FETCH FIRST 50 ROWS ONLY;
CREATE INDEX idx_post_created_on ON post (created_on DESC, id DESC);
```
Source: `sql-seek-keyset-pagination.md`.

### 2. INNER JOIN + DISTINCT used only for filtering

```java
// WRONG: forces a JOIN + DISTINCT just to filter Post by child criterion
List<Post> posts = em.createQuery("""
    select distinct p
    from PostComment pc
    join pc.post p
    where pc.score > :minScore
    """, Post.class)
  .setParameter("minScore", 10)
  .setHint(QueryHints.HINT_PASS_DISTINCT_THROUGH, false)
  .getResultList();
```

```java
// CORRECT: EXISTS SemiJoin, no DISTINCT needed, short-circuits per outer row
List<Post> posts = em.createQuery("""
    select p from Post p
    where exists (
       select 1 from PostComment pc
       where pc.post = p and pc.score > :minScore
    )
    """, Post.class)
  .setParameter("minScore", 10)
  .getResultList();
```
Source: `exists-subqueries-jpa-hibernate.md`, `sql-exists.md`.

### 3. JOIN FETCH on a Page<T>

```java
// WRONG: Hibernate logs HHH000104 and paginates in memory
List<Post> page = em.createQuery("""
    select p from Post p
    left join fetch p.comments
    order by p.createdOn
    """, Post.class)
  .setFirstResult(0).setMaxResults(10)
  .getResultList();
```

```java
// CORRECT: two-step — paginate IDs, then JOIN FETCH by IN clause
List<Long> ids = em.createQuery(
    "select p.id from Post p order by p.createdOn", Long.class)
  .setFirstResult(0).setMaxResults(10)
  .getResultList();

List<Post> posts = em.createQuery("""
    select distinct p from Post p
    left join fetch p.comments
    where p.id in :ids
    order by p.createdOn
    """, Post.class)
  .setParameter("ids", ids)
  .setHint(QueryHints.HINT_PASS_DISTINCT_THROUGH, false)
  .getResultList();
```
Source: `query-pagination-jpa-hibernate.md`, `jpql-distinct-jpa-hibernate.md`.

### 4. IN-clause exploding the plan cache

```jpql
-- WRONG: every distinct argument-count creates a new plan entry
select p from Post p where p.id in :ids
-- expands to (:ids_0), (:ids_0,:ids_1), (:ids_0,:ids_1,:ids_2), ...
```

```properties
# CORRECT: pad IN bind counts to powers of two so plans are reused
hibernate.query.in_clause_parameter_padding=true
hibernate.criteria.literal_handling_mode=bind
```
Source: `optimize-jpql-criteria-api-query-plans-hibernate-statistics.md`, `tips-oracle-jpa-hibernate.md`.

### 5. ORDER BY without supporting index

```sql
-- WRONG: full sort of post for every page
SELECT id, title FROM post ORDER BY created_on DESC LIMIT 50;
```

```sql
-- CORRECT: index in the same direction so the planner does Index Scan Backward
CREATE INDEX idx_post_created_on ON post (created_on DESC);
SELECT id, title FROM post ORDER BY created_on DESC LIMIT 50;
```
Source: `sql-seek-keyset-pagination.md`, `postgresql-index-types.md`.

### 6. Missing EXPLAIN ANALYZE on PostgreSQL

```sql
-- WRONG: only estimated plan; bind parameter values are ignored
EXPLAIN SELECT * FROM task WHERE status = 'DONE';
```

```sql
-- CORRECT: actual plan with row counts, buffers, and timing
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM task WHERE status = 'DONE';

-- Or enable auto_explain in postgresql.conf to capture production plans:
-- session_preload_libraries = 'auto_explain'
-- auto_explain.log_analyze  = 'on'
-- auto_explain.log_min_duration = '100ms'
```
Source: `index-selectivity.md`, `postgresql-auto-explain.md`.

### 7. Concatenated SQL (injection)

```java
// WRONG: review = "'; DROP TABLE post_comment; --" wipes the table on
// SQL Server, PostgreSQL, MySQL
session.doWork(c -> {
  try (Statement s = c.createStatement()) {
    s.executeUpdate(
      "UPDATE post_comment SET review = '" + review + "' WHERE id = " + id);
  }
});
```

```java
// CORRECT: bind parameters via PreparedStatement / JPA setParameter
em.createQuery(
    "update PostComment pc set pc.review = :review where pc.id = :id")
  .setParameter("review", review)
  .setParameter("id", id)
  .executeUpdate();
```
Source: `a-beginners-guide-to-sql-injection-and-how-you-should-prevent-it.md`.

### 8. Criteria API for trivial queries

```java
// WRONG: Criteria boilerplate for a query JPQL writes in one line
CriteriaBuilder cb = em.getCriteriaBuilder();
CriteriaQuery<Post> cq = cb.createQuery(Post.class);
Root<Post> p = cq.from(Post.class);
cq.where(cb.equal(p.get("id"), 1L));
Post post = em.createQuery(cq).getSingleResult();
```

```java
// CORRECT: use JPQL (or em.find) for static queries; reserve Criteria
// for genuinely dynamic predicates
Post post = em.find(Post.class, 1L);
```
Source: `the-performance-penalty-of-class-forname-when-parsing-jpql-and-criteria-queries.md` (entity-query parsing is expensive — don't pay it for trivial lookups).

### 9. OR conditions defeating the index

```sql
-- WRONG: OR across two columns prevents single-index use, may Seq Scan
SELECT * FROM post WHERE title = ? OR slug = ?;
```

```sql
-- CORRECT: UNION ALL lets each branch use its own index
SELECT * FROM post WHERE title = ?
UNION ALL
SELECT * FROM post WHERE slug = ? AND title <> ?;
```
Source: indexing principles in `index-selectivity.md`, `postgresql-index-types.md`.

### 10. Stored procedure call leaking JDBC cursors

```java
// WRONG: CallableStatement stays open until transaction commit;
// causes ORA-01000 maximum open cursors exceeded on Oracle
StoredProcedureQuery q = em.createStoredProcedureQuery("count_comments")
  .registerStoredProcedureParameter("postId", Long.class, ParameterMode.IN)
  .registerStoredProcedureParameter("c", Long.class, ParameterMode.OUT)
  .setParameter("postId", 1L);
q.execute();
Long count = (Long) q.getOutputParameterValue("c");
// no release(); CallableStatement still open
```

```java
// CORRECT: explicitly release the ProcedureOutputs
StoredProcedureQuery q = em.createStoredProcedureQuery("count_comments")
  .registerStoredProcedureParameter("postId", Long.class, ParameterMode.IN)
  .registerStoredProcedureParameter("c", Long.class, ParameterMode.OUT)
  .setParameter("postId", 1L);
q.execute();
Long count = (Long) q.getOutputParameterValue("c");
q.unwrap(ProcedureOutputs.class).release();
```
Source: `best-way-call-stored-procedure-jpa-hibernate.md`.

### 11. Missing query timeout

```java
// WRONG: a runaway query holds a connection and locks forever
List<Post> posts = em.createQuery(
    "select p from Post p where lower(p.title) like lower(:t)", Post.class)
  .setParameter("t", "%Hibernate%")
  .getResultList();
```

```java
// CORRECT: bound execution time; configure globally if appropriate
List<Post> posts = em.createQuery(
    "select p from Post p where lower(p.title) like lower(:t)", Post.class)
  .setParameter("t", "%Hibernate%")
  .setHint("jakarta.persistence.query.timeout", 1_000)  // ms
  .getResultList();

// Or apply to all queries:
// <property name="jakarta.persistence.query.timeout" value="1000"/>
```
Source: `query-timeout-jpa-hibernate.md`, `jpa-hibernate-query-hints.md`.

### 12. Reinventing aggregation with subqueries when window functions exist

```sql
-- WRONG: correlated subquery per row to compute a running balance
SELECT id, account_id, amount,
       (SELECT SUM(amount) FROM account_transaction t2
        WHERE t2.account_id = t.account_id AND t2.id <= t.id) AS balance
FROM account_transaction t;
```

```sql
-- CORRECT: a single window-function pass
SELECT id, account_id, amount,
       SUM(amount) OVER (PARTITION BY account_id
                         ORDER BY created_on, id) AS balance
FROM account_transaction;
```
Source: `why-you-should-definitely-learn-sql-window-functions.md`, `hibernate-jpql-window-functions.md`.

## Performance Pitfalls

- The estimated `EXPLAIN` plan can diverge from the production plan. On PostgreSQL `prepareThreshold=5` causes a generic plan after the 5th execution; on Oracle bind-variable peeking may have cached a plan tuned for a different parameter. Reproduce with `EXPLAIN ANALYZE` _and_ enable `auto_explain` / `GATHER_PLAN_STATISTICS` to see the actual plan [`postgresql-auto-explain.md`, `execution-plan-oracle-hibernate-query-hints.md`].
- MySQL has _no_ execution-plan cache; every execution is re-optimized using the current bind values. Plan-cache tuning advice (parameter padding, literal binding) is critical on Oracle and SQL Server but irrelevant for MySQL [`relational-database-sql-prepared-statements` linked from `index-selectivity.md`].
- MySQL traditionally only supported Nested Loops; Hash Join arrived in 8.0.18 and Merge Join is still missing. Designing a many-row analytics query on MySQL needs different assumptions than on PostgreSQL/Oracle/SQL Server [`hash-join-algorithm` linked from `execution-plan-oracle-hibernate-query-hints.md`, `merge-join-algorithm` linked from same].
- Oracle defaults JDBC `fetchSize` to 10 — every 50-row query takes 5 round trips. Always set `spring.jpa.properties.hibernate.jdbc.fetch_size` (e.g., 100) on Oracle; PostgreSQL/MySQL prefetch the whole `ResultSet` so this matters less except for streaming [`tips-oracle-jpa-hibernate.md`, `whats-new-in-jpa-2-2-stream-the-result-of-a-query-execution.md`].
- Default indexes vary by vendor: Oracle/SQL Server/PostgreSQL index PK and UK but _not_ FK; MySQL indexes FK too. On the first three, large FK joins without an explicit index fall back to table scans [`index-selectivity.md` linked content].
- Random UUID PKs cause B+Tree page splits, low fill factor, and bloat every secondary index leaf (clustered indexes embed the PK). Use a time-sorted TSID (64-bit) or, at minimum, UUIDv7 [`clustered-index.md`].
- `Class.forName` was historically invoked for every dotted expression in JPQL parsing, causing classloader lock contention (especially on WebLogic). Stick to Java naming conventions (`com.acme.Foo.CONSTANT_NAME`) so Hibernate's pattern check filters out aliases; otherwise set `hibernate.query.conventional_java_constants=false` [`the-performance-penalty-of-class-forname-when-parsing-jpql-and-criteria-queries.md`].
- The slow query log won't catch N+1: each individual SELECT runs under the threshold; only the aggregate is slow. Use Hibernate `Statistics` or a `StatementInspector` to count queries per request [`hibernate-slow-query-log.md`, `n-plus-1-query-problem.md`, `source-sql-query-hibernate.md`].
- Hibernate's `StoredProcedureQuery` keeps the JDBC `CallableStatement` open until the transaction ends. On Oracle this hits `ORA-01000` quickly under load. Always call `query.unwrap(ProcedureOutputs.class).release()` [`best-way-call-stored-procedure-jpa-hibernate.md`].
- `Query.stream()` only avoids loading the whole `ResultSet` if the underlying JDBC fetch is cursored. PostgreSQL needs `setHint(QueryHints.HINT_FETCH_SIZE, N)`; MySQL needs `fetchSize=Integer.MIN_VALUE` or the `useCursorFetch=true` connection property; otherwise the entire result is buffered client-side [`whats-new-in-jpa-2-2-stream-the-result-of-a-query-execution.md`, `9-high-performance-tips-when-using-mysql-with-jpa-and-hibernate.md`].
- `JPQL DISTINCT` is passed through to SQL by default, forcing the DB to sort/dedupe. When you only need Java-object deduplication (typical for `JOIN FETCH`), set `QueryHints.HINT_PASS_DISTINCT_THROUGH=false` to strip the SQL keyword while still deduping the entity list [`jpql-distinct-jpa-hibernate.md`, `exists-subqueries-jpa-hibernate.md`].
- IN-clause queries generate a fresh execution-plan entry per distinct parameter count, blowing the plan cache on Oracle and SQL Server. Enable `hibernate.query.in_clause_parameter_padding=true` to round bind counts to powers of two [`optimize-jpql-criteria-api-query-plans-hibernate-statistics.md`, `tips-oracle-jpa-hibernate.md`].
- SQL Server inserts strings as Unicode by default (`sendStringParametersAsUnicode=true`), which can defeat indexes on `varchar` columns and produce implicit conversions in the plan. Set the property to `false` when columns are non-Unicode [`tips-oracle-jpa-hibernate.md` linked tips, `9-high-performance-tips-when-using-mysql-with-jpa-and-hibernate.md` (analogous JDBC tuning)].
- Native dialect features (PostgreSQL `LATERAL`/`JSON_TABLE`, SQL Server `OPENJSON`/`CROSS APPLY`, Oracle `MERGE`/`MATCH_RECOGNIZE`, recursive CTEs) are unreachable via JPQL on older Hibernate versions. Drop to `createNativeQuery` rather than emulating in Java [`sql-server-openjson.md`, `hibernate-with-recursive-query.md`, `tips-oracle-jpa-hibernate.md`, `9-postgresql-high-performance-performance-tips.md`].

## Citations

- `9-high-performance-tips-when-using-mysql-with-jpa-and-hibernate.md` — MySQL-specific tuning: avoid `GenerationType.AUTO`, IDENTITY disables batch inserts, ResultSet streaming caveats, prepared-statement emulation.
- `9-postgresql-high-performance-performance-tips.md` — PostgreSQL tuning: MVCC, shared buffers, window functions, JSON, advisory locks, `reWriteBatchedInserts`.
- `a-beginners-guide-to-sql-injection-and-how-you-should-prevent-it.md` — Concrete injection demos on Oracle/SQL Server/PostgreSQL; bind parameters always.
- `best-way-call-stored-procedure-jpa-hibernate.md` — `StoredProcedureQuery` leaks `CallableStatement`; call `ProcedureOutputs.release()`.
- `clustered-index.md` — B+Tree internals, clustered vs heap tables, SQL Server `INCLUDE`, compact monotonic PKs.
- `execution-plan-oracle-hibernate-query-hints.md` — Capturing Oracle actual plans via `GATHER_PLAN_STATISTICS` + `addQueryHint` + `dbms_xplan.display_cursor`.
- `execution-plan-sql-server.md` — `SHOWPLAN_ALL`, `SET STATISTICS IO, TIME, PROFILE ON`, SSMS Ctrl+L/Ctrl+M.
- `exists-subqueries-jpa-hibernate.md` — EXISTS SemiJoin over INNER JOIN + DISTINCT; JPQL/Criteria/Blaze examples.
- `hibernate-jpql-window-functions.md` — Hibernate 6 SQM supports window functions in JPQL (`ROW_NUMBER`, `SUM OVER`).
- `hibernate-slow-query-log.md` — `hibernate.log_slow_query=N` and `org.hibernate.SQL_SLOW` logger.
- `hibernate-with-recursive-query.md` — Hierarchical fetches via recursive CTE in Hibernate.
- `index-selectivity.md` — Skewed columns and partial indexes; how the optimizer skips low-selectivity indexes.
- `jpa-hibernate-query-hints.md` — Catalog of JPA + Hibernate query hints (timeout, fetchgraph, comment, fetchSize, cacheable, PASS_DISTINCT_THROUGH).
- `jpa-query-setparameter-hibernate.md` — Binding custom types (JSON) via `setParameter`; avoid the "bytea vs jsonb" PostgreSQL error.
- `jpql-distinct-jpa-hibernate.md` — `HINT_PASS_DISTINCT_THROUGH=false` strips SQL DISTINCT while still deduping entities.
- `keyset-pagination-jpa-hibernate.md` — Blaze Persistence keyset pagination over JPA/Hibernate with `PagedList`.
- `n-plus-1-query-problem.md` — Definition, why slow query log misses it, fix via JOIN FETCH / entity graphs / batch size.
- `optimize-jpql-criteria-api-query-plans-hibernate-statistics.md` — IN-clause padding and `Statistics.getQueries()` to inspect plan-cache pressure.
- `pagination-best-practices.md` — Cap pages, prefer better filters over deep navigation, page-number limits.
- `postgresql-auto-explain.md` — `session_preload_libraries=auto_explain`, `log_min_duration` to capture production actual plans.
- `postgresql-index-types.md` — B+Tree, Hash, GIN; range scans, sorted scans, INCLUDE columns.
- `query-pagination-jpa-hibernate.md` — `setFirstResult`/`setMaxResults`, dialect-specific LIMIT/FETCH NEXT, ORDER BY mandatory.
- `query-timeout-jpa-hibernate.md` — `jakarta.persistence.query.timeout` (ms) vs `org.hibernate.timeout` (s); global property option.
- `source-sql-query-hibernate.md` — `StatementInspector` + Hypersistence `QueryStackTraceLogger` to attribute SQL back to caller.
- `sql-cte-common-table-expression.md` — CTE/`WITH` for multi-step queries; window functions inside CTEs.
- `sql-exists.md` — EXISTS / NOT EXISTS semantics; short-circuit behavior.
- `sql-execution-plan-oracle.md` — `EXPLAIN PLAN FOR`, `DBMS_XPLAN.DISPLAY`, `GATHER_PLAN_STATISTICS`, `STATISTICS_LEVEL=ALL`.
- `sql-query-limit-top-n-rows.md` — Top-N via `FETCH FIRST N ROWS ONLY`; plan difference Seq Scan vs Index Scan + Limit.
- `sql-seek-keyset-pagination.md` — Seek/keyset SQL: row-value `(sort_col, id) < (?, ?)` with matching composite index.
- `sql-server-openjson.md` — `OPENJSON ... WITH (...)` to project JSON into a relational shape.
- `the-performance-penalty-of-class-forname-when-parsing-jpql-and-criteria-queries.md` — JPQL parser used to call `Class.forName` per dotted expression; fix relies on Java constant naming conventions.
- `tips-oracle-jpa-hibernate.md` — Oracle: buffer pool, plan cache, `implicitStatementCacheSize`, fetch_size=100, IN-clause padding, `@RowId`, JSON storage.
- `whats-new-in-jpa-2-2-stream-the-result-of-a-query-execution.md` — `Query.stream()` requires JDBC fetch_size to actually cursor; otherwise the whole ResultSet is buffered.
- `why-you-should-definitely-learn-sql-window-functions.md` — Window functions for ranking, running totals, partition-aware updates.
