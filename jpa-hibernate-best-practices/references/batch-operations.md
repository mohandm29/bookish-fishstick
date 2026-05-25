# Batch Operations (Insert / Update / Delete)

## Core Principles

- Set `hibernate.jdbc.batch_size` to a non-zero value (typically 10-50) to enable JDBC `addBatch`/`executeBatch` grouping. Without it, every INSERT/UPDATE/DELETE is sent as a separate round-trip, even inside a single flush [`how-to-batch-insert-and-update-statements-with-hibernate.md`].
- Set `hibernate.order_inserts=true` and `hibernate.order_updates=true` whenever the unit of work touches more than one entity type. A JDBC batch can target only one table, so a new DML against a different table closes the current batch and opens a new one [`how-to-batch-insert-and-update-statements-with-hibernate.md`].
- Set `hibernate.jdbc.batch_versioned_data=true` to enable UPDATE batching for `@Version` entities. The driver must return correct row counts from `executeBatch()`; this is true for all modern PostgreSQL, MySQL, Oracle, and SQL Server drivers [`how-to-batch-insert-and-update-statements-with-hibernate.md`].
- Do not use `GenerationType.IDENTITY` for entities you intend to batch-insert. IDENTITY forces Hibernate to execute the INSERT immediately on `persist()` to obtain the generated key, which silently disables INSERT batching [`batch-insert-mysql-hibernate.md`].
- Inside the batch loop, call `entityManager.flush()` then `entityManager.clear()` every `batchSize` iterations. `flush()` sends the batch to the database; `clear()` evicts managed entities to keep the Persistence Context (and dirty-checking cost) bounded [`the-best-way-to-do-batch-processing-with-jpa-and-hibernate.md`, `how-to-batch-insert-and-update-statements-with-hibernate.md`].
- Prefer `StatelessSession` for large data-import jobs that do not need cascading, dirty checking, second-level cache, or the first-level cache. A `StatelessSession` has no Persistence Context, so it cannot leak memory and works well with high batch sizes [`batch-insert-mysql-hibernate.md`].
- Use bulk JPQL/HQL (`UPDATE Entity e SET … WHERE …`, `DELETE FROM Entity e WHERE …`) when you can express the modification as a predicate. A single SQL statement is almost always faster than loading entities and modifying them one by one [`bulk-update-delete-jpa-hibernate.md`].
- Use the JPA Criteria API (`CriteriaUpdate`, `CriteriaDelete`) when the bulk predicate or target entity type is built dynamically (e.g. moderation across several subtypes) [`jpa-criteria-api-bulk-update-delete.md`].
- On PostgreSQL, also add `reWriteBatchedInserts=true` to the JDBC URL. Without it, the driver still ships each parameter set as a separate `INSERT`; with it, the driver rewrites them into a single multi-row `INSERT … VALUES (…),(…)` [`batch-insert-mysql-hibernate.md`].
- Commit the surrounding transaction every batch (or every few batches) to keep undo/redo log size, lock retention, and rollback cost bounded; otherwise a single failure at row 999,999 forces the DB to undo everything [`the-best-way-to-do-batch-processing-with-jpa-and-hibernate.md`].
- When a `BatchUpdateException` is thrown, the failed statement is the one at index `getUpdateCounts().length` — i.e. the first one whose count is missing from the success array. Always log this offset; otherwise you cannot tell which row in a 50-row batch caused the failure [`how-to-find-which-statement-failed-in-a-jdbc-batch-update.md`].
- Bulk JPQL and Criteria DML bypass the Persistence Context. Either run them in a fresh session, or call `entityManager.flush()` and `entityManager.clear()` before/after to keep cached entities consistent with the database [`bulk-update-delete-jpa-hibernate.md`, `jpa-criteria-api-bulk-update-delete.md`].

## Decision Trees

### Inserting > 100 rows of the same entity?

```
Does the @Id use GenerationType.IDENTITY?
├── YES → IDENTITY disables INSERT batching.
│         ├── Can you switch to SEQUENCE (or TABLE on MySQL via a pooled-lo strategy)?
│         │   └── Switch the generator; set hibernate.jdbc.batch_size=N; persist() in a flush/clear loop.
│         └── Stuck on MySQL IDENTITY?
│             └── Use a StatelessSession + @SQLInsert with a NoOpGenerator so Hibernate
│                 emits a true batched INSERT and lets MySQL assign the id
│                 [batch-insert-mysql-hibernate.md].
└── NO  → Set hibernate.jdbc.batch_size, order_inserts=true,
           and use the flush/clear loop pattern shown below
           [how-to-batch-insert-and-update-statements-with-hibernate.md,
            the-best-way-to-do-batch-processing-with-jpa-and-hibernate.md].
```

### Deleting many rows?

```
Can you express the rows to delete as a SQL WHERE predicate?
├── YES, predicate is static                                 → JPQL `DELETE FROM Entity e WHERE …`
│                                                              [bulk-update-delete-jpa-hibernate.md]
├── YES, predicate is built dynamically at runtime           → CriteriaDelete
│                                                              [jpa-criteria-api-bulk-update-delete.md]
├── NO — you must delete entities you already loaded
│   ├── No associations / no cascading required              → StatelessSession.delete in a loop
│   │                                                          [batch-insert-mysql-hibernate.md]
│   └── Cascading / orphanRemoval required                   → Entity loop with persist/remove + flush/clear,
│                                                              batch_size set, order_updates=true
│                                                              [how-to-batch-delete-statements-with-hibernate.md]
```

### Need cascading on inserts/updates?

```
Does any reachable entity need cascade = PERSIST/MERGE/REMOVE?
├── YES → Use a normal Session/EntityManager with batch_size + order_inserts.
│         StatelessSession does NOT cascade and does NOT fire entity listeners
│         [batch-insert-mysql-hibernate.md].
└── NO  → Prefer StatelessSession; it skips the Persistence Context and dirty
          checking and is the fastest path for bulk imports
          [batch-insert-mysql-hibernate.md].
```

### Mixed entity types in the same unit of work?

```
You are persisting Posts and PostComments (or any 2+ tables) in one transaction.
├── order_inserts NOT set → each switch from Post→Comment→Post breaks the batch.
│                            JDBC will send Post, Comment, Post, Comment … one by one
│                            [how-to-batch-insert-and-update-statements-with-hibernate.md].
└── order_inserts=true   → Hibernate groups all Post inserts first, then all Comment
                            inserts; two large batches instead of N small ones
                            [how-to-batch-insert-and-update-statements-with-hibernate.md].
```

### Need a different batch size for one specific use case?

```
Default batch_size of 30 is fine for OLTP, but the nightly ETL wants 100.
└── Open the Session manually and call session.setJdbcBatchSize(100); the per-session
    setting overrides the global property for that Persistence Context only
    [how-to-customize-the-jdbc-batch-size-for-each-persistence-context-with-hibernate.md].
```

## Anti-patterns: WRONG / CORRECT

### 1. Loop persist() with no flush/clear — Persistence Context grows unbounded

WRONG — heap pressure, dirty-check cost grows O(N²), eventually `OutOfMemoryError`:

```java
@Transactional
public void importPosts(List<PostDto> dtos) {
    for (PostDto dto : dtos) {                       // 1,000,000 entries
        Post p = new Post();
        p.setTitle(dto.title());
        entityManager.persist(p);                    // never flushed, never cleared
    }
    // single huge flush at commit; OOM long before then
}
```

CORRECT — flush and clear every batch boundary [`the-best-way-to-do-batch-processing-with-jpa-and-hibernate.md`, `how-to-batch-insert-and-update-statements-with-hibernate.md`]:

```java
@Transactional
public void importPosts(List<PostDto> dtos) {
    int batchSize = 50;                              // match hibernate.jdbc.batch_size
    for (int i = 0; i < dtos.size(); i++) {
        Post p = new Post();
        p.setTitle(dtos.get(i).title());
        entityManager.persist(p);
        if (i > 0 && i % batchSize == 0) {
            entityManager.flush();                   // send the batch
            entityManager.clear();                   // evict managed entities
        }
    }
}
```

### 2. GenerationType.IDENTITY silently disables INSERT batching

WRONG — log shows `Batch:False, BatchSize:0` even with `hibernate.jdbc.batch_size=50` [`batch-insert-mysql-hibernate.md`]:

```java
@Entity
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // forces INSERT on persist()
    private Long id;
}
```

CORRECT — use `SEQUENCE` (preferred) so Hibernate can defer the INSERT until flush time [`batch-insert-mysql-hibernate.md`]:

```java
@Entity
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post_seq")
    @SequenceGenerator(name = "post_seq", sequenceName = "post_seq", allocationSize = 50)
    private Long id;
}
```

On MySQL (no sequences), fall back to `StatelessSession` + `@SQLInsert` with a no-op id generator so the INSERT is still batched [`batch-insert-mysql-hibernate.md`]:

```java
StatelessSession ss = sessionFactory.openStatelessSession();
ss.setJdbcBatchSize(50);
Transaction tx = ss.beginTransaction();
for (PostDto dto : dtos) {
    ss.insert(new BatchInsertPost().setTitle(dto.title()));   // batched insert, id assigned by MySQL
}
tx.commit();
ss.close();
```

### 3. Missing `hibernate.jdbc.batch_size` configuration

WRONG — Hibernate ships every statement individually; the loop pattern looks right but the database still sees N round-trips [`how-to-batch-insert-and-update-statements-with-hibernate.md`]:

```properties
# application.properties — no batch_size, no ordering
spring.jpa.hibernate.ddl-auto=none
```

CORRECT — three properties together are the minimum [`how-to-batch-insert-and-update-statements-with-hibernate.md`]:

```properties
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
spring.jpa.properties.hibernate.jdbc.batch_versioned_data=true
```

### 4. Entity-loop delete instead of bulk JPQL

WRONG — N SELECTs (or one fetch) + N DELETE statements, plus cascade traversal [`bulk-update-delete-jpa-hibernate.md`]:

```java
@Transactional
public void purgeSpam() {
    List<Post> spam = entityManager
        .createQuery("from Post p where p.status = 'SPAM'", Post.class)
        .getResultList();
    for (Post p : spam) {
        entityManager.remove(p);                     // dirty-check, cascade, version check, …
    }
}
```

CORRECT — single SQL statement, no entities loaded [`bulk-update-delete-jpa-hibernate.md`]:

```java
@Transactional
public int purgeSpam(LocalDateTime cutoff) {
    return entityManager.createQuery("""
            delete from Post p
            where p.status = 'SPAM'
              and p.createdOn < :cutoff
            """)
        .setParameter("cutoff", cutoff)
        .executeUpdate();
}
```

When the predicate or target type is dynamic, use `CriteriaDelete` [`jpa-criteria-api-bulk-update-delete.md`]:

```java
public <T extends PostModerate> int deleteSpam(Class<T> type, LocalDateTime cutoff) {
    CriteriaBuilder cb = entityManager.getCriteriaBuilder();
    CriteriaDelete<T> delete = cb.createCriteriaDelete(type);
    Root<T> root = delete.from(type);
    delete.where(
        cb.equal(root.get("status"), PostStatus.SPAM),
        cb.lessThan(root.get("createdOn"), cutoff)
    );
    return entityManager.createQuery(delete).executeUpdate();
}
```

### 5. Mixed entity types without `order_inserts`

WRONG — Post / Comment / Post / Comment interleaving breaks every batch after one row [`how-to-batch-insert-and-update-statements-with-hibernate.md`]:

```java
@Transactional
public void seed(int n) {
    for (int i = 0; i < n; i++) {
        Post p = new Post("Post " + i);
        p.addComment(new Comment("c1"));
        p.addComment(new Comment("c2"));
        entityManager.persist(p);                    // logs show BatchSize:1 for every insert
    }
}
```

CORRECT — same Java code, but configure ordering so Hibernate emits all Posts in one batch and all Comments in another [`how-to-batch-insert-and-update-statements-with-hibernate.md`]:

```properties
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
```

### 6. Spring Data `save()` per row instead of `saveAll()`

WRONG — `save()` opens a new transaction per call (when no surrounding `@Transactional`) and flushes between rows, defeating batching:

```java
public void importAll(List<PostDto> dtos) {
    for (PostDto dto : dtos) {
        postRepository.save(toEntity(dto));          // flush + commit each iteration
    }
}
```

CORRECT — one transaction, `saveAll()` (or `persistAll` from `BaseJpaRepository`) with chunking that matches `batch_size` [`batch-insert-mysql-hibernate.md`, `the-best-way-to-do-batch-processing-with-jpa-and-hibernate.md`]:

```java
@Transactional
public void importAll(List<PostDto> dtos) {
    int batchSize = 50;
    for (int i = 0; i < dtos.size(); i += batchSize) {
        List<Post> chunk = dtos.subList(i, Math.min(i + batchSize, dtos.size()))
            .stream().map(this::toEntity).toList();
        postRepository.saveAll(chunk);
        entityManager.flush();
        entityManager.clear();
    }
}
```

### 7. PostgreSQL without `reWriteBatchedInserts=true`

WRONG — even with `hibernate.jdbc.batch_size=50`, the pgJDBC driver sends 50 individual INSERTs over the wire [`batch-insert-mysql-hibernate.md`]:

```
jdbc:postgresql://localhost:5432/app
```

CORRECT — driver rewrites grouped inserts into multi-row VALUES, cutting round-trips dramatically [`batch-insert-mysql-hibernate.md`]:

```
jdbc:postgresql://localhost:5432/app?reWriteBatchedInserts=true
```

### 8. CascadeType.REMOVE on a one-to-many breaks DELETE batching

WRONG — Hibernate must walk children first; `Post` deletes come one at a time, only sibling `Comment` deletes get batched [`how-to-batch-delete-statements-with-hibernate.md`]:

```java
@Entity
public class Post {
    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Comment> comments = new ArrayList<>();
}
// session.delete(post);   // → 1 delete for Post, 1 for PostDetails, batched only for Comments
```

CORRECT — for mass purges, push the cascade to the database with `ON DELETE CASCADE` and use a bulk JPQL delete, or use `orphanRemoval` carefully and pre-load with a JOIN FETCH so the batch executor can group siblings together [`how-to-batch-delete-statements-with-hibernate.md`]:

```java
// Option A: bulk JPQL with DB-side cascade
entityManager.createQuery("delete from Post p where p.status = 'SPAM'").executeUpdate();

// Option B: keep cascade but fetch+order so Hibernate groups deletes per table
List<Post> posts = entityManager.createQuery(
        "select p from Post p left join fetch p.comments left join fetch p.details",
        Post.class).getResultList();
posts.forEach(entityManager::remove);                 // with order_updates=true, deletes group per table
```

### 9. Catching `BatchUpdateException` without inspecting `getUpdateCounts()`

WRONG — log says "batch failed" but not which row [`how-to-find-which-statement-failed-in-a-jdbc-batch-update.md`]:

```java
try {
    statement.executeBatch();
} catch (BatchUpdateException e) {
    log.error("Batch failed", e);                    // useless for forensics
    throw e;
}
```

CORRECT — `getUpdateCounts().length` is the index of the first failing statement [`how-to-find-which-statement-failed-in-a-jdbc-batch-update.md`]:

```java
try {
    statement.executeBatch();
} catch (BatchUpdateException e) {
    int firstFailingIndex = e.getUpdateCounts().length;
    log.error("Batch failed at statement #{}: {}", firstFailingIndex, e.getMessage(), e);
    throw e;
}
```

### 10. One global `batch_size` for both OLTP and ETL

WRONG — OLTP only ever flushes 1-3 rows, but a single global `batch_size=200` keeps memory pinned for every request.

CORRECT — leave the global at an OLTP-friendly value (e.g. 15) and bump it per-session for the import job [`how-to-customize-the-jdbc-batch-size-for-each-persistence-context-with-hibernate.md`]:

```java
Session session = entityManager.unwrap(Session.class);
session.setJdbcBatchSize(100);                       // applies only to this Persistence Context
```

## Performance Pitfalls

- **Flush without clear → heap pressure and quadratic dirty-checking.** Every flushed-but-not-cleared entity remains managed; the next flush re-scans every one of them. Always pair `flush()` with `clear()` inside the batch loop [`the-best-way-to-do-batch-processing-with-jpa-and-hibernate.md`, `how-to-batch-insert-and-update-statements-with-hibernate.md`].
- **`GenerationType.IDENTITY` silently disables INSERT batching.** The Hibernate logs will show `Batch:False, BatchSize:0` no matter what `hibernate.jdbc.batch_size` you set. Switch to `SEQUENCE` (with `allocationSize` ≥ batch size) on databases that support it; on MySQL use the `StatelessSession` + `@SQLInsert` workaround [`batch-insert-mysql-hibernate.md`].
- **Mixed entity types break batches even when `batch_size` is set.** A JDBC batch is per-PreparedStatement (per table). Without `hibernate.order_inserts=true` / `order_updates=true`, every cross-table interleave starts a new batch of size 1 [`how-to-batch-insert-and-update-statements-with-hibernate.md`].
- **Loading entities just to delete them.** Entity-loop deletes generate one SELECT (or fetch) plus N DELETEs plus cascade traversal plus optimistic-lock version checks. A bulk JPQL `DELETE` is a single SQL round-trip with no entities loaded — typically orders of magnitude faster [`bulk-update-delete-jpa-hibernate.md`].
- **`CascadeType.REMOVE` and JDBC batching mix poorly.** The cascade traversal forces multiple table targets in one operation; only the leaf entity deletes can be batched. Push cascading to the database (`ON DELETE CASCADE`) for large-volume deletes [`how-to-batch-delete-statements-with-hibernate.md`].
- **Long-running batch transactions.** One transaction covering 1M rows holds undo/redo, accumulates locks, balloons the rollback segment, and forces a full re-run on any failure. Commit every batch (or every K batches) — `the-best-way-to-do-batch-processing-with-jpa-and-hibernate.md` explicitly recommends `commit(); begin()` inside the loop.
- **Driver does not rewrite batched inserts.** On PostgreSQL, without `reWriteBatchedInserts=true`, Hibernate's batching only reduces JDBC API overhead, not network round-trips. The URL parameter unlocks multi-row INSERT rewriting at the driver layer [`batch-insert-mysql-hibernate.md`].
- **Bulk JPQL/Criteria DML bypasses the Persistence Context.** Cached entities will be stale and the second-level cache will be out of sync. Flush and clear (and evict from L2 cache if used) around any bulk DML [`bulk-update-delete-jpa-hibernate.md`, `jpa-criteria-api-bulk-update-delete.md`].
- **Spring Data `save()` per row** opens a new transaction (when no enclosing `@Transactional`) and flushes between rows. Always wrap import code in `@Transactional` and use `saveAll(chunk)` with chunks sized to `hibernate.jdbc.batch_size`.
- **Diagnosing a `BatchUpdateException` without `getUpdateCounts()`.** The driver returns success counts for every statement that ran before the failure; the failing statement's index is `getUpdateCounts().length`. Without this, you cannot pinpoint which row in a 50-row batch poisoned the transaction [`how-to-find-which-statement-failed-in-a-jdbc-batch-update.md`].

- StatelessSession skips cascading and listeners — fast for raw imports but breaks `@PrePersist`, Envers, and cascade=ALL relationships.
- Sequence `allocationSize` must be at least the JDBC batch size or Hibernate round-trips for ids mid-batch.
- Hibernate's SQL log alone does not prove batching; use a JDBC proxy that prints `Batch:True, BatchSize:N`.

## Verification Checklist

Before declaring a batch path "fast", verify each of the following with a logging proxy that exposes the JDBC batch counts (do not trust Hibernate's SQL log on its own):

1. The logged line reads `Batch:True` with `BatchSize` equal to your configured `hibernate.jdbc.batch_size`.
2. Cross-table operations are grouped — all `Post` inserts before any `Comment` inserts — confirming `hibernate.order_inserts=true`.
3. UPDATEs against `@Version`-annotated entities are also batched, confirming `hibernate.jdbc.batch_versioned_data=true`.
4. Heap usage stays flat across the import (sawtooth around the batch size), confirming `clear()` is releasing managed entities.
5. On PostgreSQL, `pg_stat_statements` shows multi-row INSERT plans, confirming `reWriteBatchedInserts=true` is active.
6. Bulk DML is preceded by `flush()` if pending changes exist, and followed by `clear()` so stale managed copies are not reused.
7. When a batch fails in production, the error handler logs `getUpdateCounts().length` so the offending row can be located.

## Citations

- `[batch-insert-mysql-hibernate.md]` — How to batch INSERT with MySQL when IDENTITY disables batching, using `StatelessSession` + `@SQLInsert` + no-op generator.
- `[bulk-update-delete-blaze-persistence.md]` — Multi-table bulk UPDATE/DELETE via Blaze-Persistence when JPQL/Criteria are insufficient.
- `[bulk-update-delete-jpa-hibernate.md]` — JPQL `UPDATE … SET …` and `DELETE FROM …` for set-based modifications without loading entities.
- `[how-to-batch-delete-statements-with-hibernate.md]` — DELETE batching, why cascade and orphanRemoval defeat it, and SQL-side cascade as a fix.
- `[how-to-batch-insert-and-update-statements-with-hibernate.md]` — Required properties (`batch_size`, `order_inserts`, `order_updates`, `batch_versioned_data`) and the flush/clear loop pattern.
- `[how-to-customize-the-jdbc-batch-size-for-each-persistence-context-with-hibernate.md]` — Overriding the global `hibernate.jdbc.batch_size` per Session via `session.setJdbcBatchSize(N)`.
- `[how-to-find-which-statement-failed-in-a-jdbc-batch-update.md]` — Using `BatchUpdateException.getUpdateCounts().length` to identify the failing statement in a batch.
- `[jpa-criteria-api-bulk-update-delete.md]` — `CriteriaUpdate` / `CriteriaDelete` for dynamic bulk DML over runtime-chosen entity types.
- `[the-best-way-to-do-batch-processing-with-jpa-and-hibernate.md]` — Reference batch-processing template: chunked commits, flush+clear cadence, matching batch sizes.

## Quick Reference: Property Cheatsheet

| Property                                       | Recommended value     | Why                                                                                  |
| ---------------------------------------------- | --------------------- | ------------------------------------------------------------------------------------ |
| `hibernate.jdbc.batch_size`                    | 10-50                 | Enables JDBC `addBatch`/`executeBatch` grouping.                                     |
| `hibernate.order_inserts`                      | `true`                | Groups INSERTs by table so a batch is not broken by entity-type interleaving.        |
| `hibernate.order_updates`                      | `true`                | Same as above for UPDATEs (and DELETEs of related entities).                         |
| `hibernate.jdbc.batch_versioned_data`          | `true`                | Allows UPDATE batching for `@Version` entities; safe on all modern drivers.          |
| `reWriteBatchedInserts` (pgJDBC URL parameter) | `true`                | Driver-level multi-row INSERT rewriting; complements Hibernate batching.             |
| `rewriteBatchedStatements` (MySQL URL)         | `true`                | The MySQL Connector/J equivalent of the PostgreSQL flag.                             |
| Sequence generator `allocationSize`            | ≥ `batch_size`        | Prevents Hibernate from fetching new ids in the middle of a batch.                   |

## Quick Reference: When to Use What

| Scenario                                          | Tool                                       |
| ------------------------------------------------- | ------------------------------------------ |
| Insert many entities with cascades and listeners  | `EntityManager` + `flush()`/`clear()` loop |
| Insert many entities, no cascades, max speed      | `StatelessSession.insert(...)`             |
| Update many rows with a static predicate          | JPQL `UPDATE Entity e SET … WHERE …`       |
| Update many rows with a runtime-built predicate   | `CriteriaUpdate`                           |
| Delete many rows with a static predicate          | JPQL `DELETE FROM Entity e WHERE …`        |
| Delete many rows with a runtime-built predicate   | `CriteriaDelete`                           |
| Per-session batch size override (e.g. nightly ETL)| `session.setJdbcBatchSize(N)`              |
