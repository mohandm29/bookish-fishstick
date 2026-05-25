# Locking and Concurrency Control

## Core Principles

- Add `@Version` to every mutable entity to prevent lost updates across HTTP requests — Hibernate appends `version = ?` to the UPDATE/DELETE WHERE clause and throws `OptimisticLockException` when row-count is 0, the only check that survives commit-between-reads [optimistic-locking-version-property-jpa-hibernate.md]
- Prefer optimistic locking (`@Version`) over `SELECT ... FOR UPDATE` for typical web flows — pessimistic locking only protects within a single physical transaction and holds DB locks that hurt throughput; optimistic locking detects conflicts without acquiring extra DB locks [a-beginners-guide-to-database-locking-and-the-lost-update-phenomena.md]
- Use `LockModeType.OPTIMISTIC` when the current transaction reads (but does not modify) an entity whose unchanged state must remain valid at commit — implicit version checks only fire when Hibernate writes the row [hibernate-locking-patterns-how-does-optimistic-lock-mode-work.md]
- Use `LockModeType.OPTIMISTIC_FORCE_INCREMENT` when a child-entity change must bump the parent aggregate's version so concurrent siblings observe a conflict — both the version check and increment happen in one UPDATE under the DB isolation level [hibernate-locking-patterns-how-does-optimistic_force_increment-lock-mode-work.md]
- Use `LockModeType.PESSIMISTIC_FORCE_INCREMENT` for fail-fast aggregate locking — version increments immediately on lock acquisition rather than at commit, surfacing `StaleObjectStateException` before doing more work [hibernate-locking-patterns-how-does-pessimistic_force_increment-lock-mode-work.md]
- Reach for `PESSIMISTIC_WRITE` only for short-lived "claim and process" sections (queues, counters, sequence generators) — it issues `FOR UPDATE`, blocks all readers/writers, and must be released before any user think-time [hibernate-locking-patterns-how-do-pessimistic_read-and-pessimistic_write-work.md]
- Use `PESSIMISTIC_READ` (`FOR SHARE`) only when you truly need shared locks; on databases without share locks Spring/Hibernate silently downgrade to `PESSIMISTIC_WRITE`, so behavior is portable but coarser [spring-data-jpa-locking.md]
- For job-queue / worker patterns, combine `PESSIMISTIC_WRITE` with `SKIP LOCKED` (Hibernate `LockOptions.SKIP_LOCKED`) so concurrent workers atomically claim disjoint rows instead of contending on the same head — vital for scaling pollers [hibernate-locking-patterns-how-do-pessimistic_read-and-pessimistic_write-work.md]
- Always retry `OptimisticLockException` outside the failed transaction — the Persistence Context is discarded on rollback, so retry must reopen a fresh transaction and re-load the entity; in-transaction retry leaks stale state [optimistic-locking-retry-with-jpa.md]
- For bulk UPDATE/DELETE JPQL, include `version = version + 1` (or use `update versioned`) — JPA bulk updates bypass entity-level optimistic locking and silently overwrite concurrent reads otherwise [bulk-update-optimistic-locking.md]
- Acquire multi-row locks in a globally consistent order (e.g., always ascending PK) — the database cannot reorder lock requests and chooses a victim only after a deadlock cycle is detected, so the application must prevent cycles [database-deadlock.md][lock-processing-logic-by-customer.md]
- Never request locks via raw SQL strings (`"... for update"` in JPQL) — use `LockModeType` so Hibernate emits the correct dialect (`FOR SHARE`, `WITH UPDLOCK`, `FOR UPDATE WITH RS`, etc.) [hibernate-locking-patterns-how-do-pessimistic_read-and-pessimistic_write-work.md][spring-data-jpa-locking.md]
- Prefer a logical clock (incrementing `int`/`short`) over a `@Version Timestamp` — system clocks drift, jump backward on NTP sync, and (prior to MySQL 5.6.4) have only second precision, so two writes in the same second silently overwrite each other [logical-vs-physical-clock-optimistic-locking.md]
- Choose your isolation level with the lock model in mind — 2PL Serializable (SQL Server default, MySQL Serializable) acquires read locks on every SELECT and is deadlock-prone, while MVCC isolation levels (PostgreSQL, Oracle, MySQL non-Serializable) need optimistic locking to prevent lost updates [2pl-two-phase-locking.md][how-does-mvcc-multi-version-concurrency-control-work.md]
- Know that PostgreSQL Repeatable Read and MySQL/Oracle Snapshot Isolation do NOT prevent Write Skew — if your business invariant spans multiple rows (e.g., "Post and PostDetails must be in sync"), you must explicitly lock both rows with `PESSIMISTIC_WRITE` or use Serializable isolation [a-beginners-guide-to-read-and-write-skew-phenomena.md][write-skew-2pl-mvcc.md]
- On SQL Server, expect child UPDATEs that touch a foreign key column to acquire a shared lock on the parent row even under `READ_COMMITTED_SNAPSHOT` — refactor updates to exclude the FK column when unchanged, or accept the contention [sql-server-foreign-key-locking.md]

## Decision Trees

### Pick a lock mode
- Is the entity being modified in this transaction?
  - Yes -> `@Version` (implicit optimistic) is sufficient; no explicit lock needed
  - No, but its value influences a decision (read-then-write across aggregates) -> `LockModeType.OPTIMISTIC`
- Will a child entity change invalidate a parent invariant (aggregate consistency)?
  - Yes, you read the parent only -> `OPTIMISTIC_FORCE_INCREMENT`
  - Yes, and you want fail-fast / serialized writers -> `PESSIMISTIC_FORCE_INCREMENT`
- Must you serialize a short critical section across nodes (counter, balance debit, queue head)?
  - Yes -> `PESSIMISTIC_WRITE` (+ `SKIP LOCKED` for queues; + `NOWAIT` for fail-fast)
- Need shared read locks (rare; usually wrong)?
  - Yes -> `PESSIMISTIC_READ`, but plan for downgrade to `PESSIMISTIC_WRITE` on MSSQL/Oracle [hibernate-locking-patterns-how-do-pessimistic_read-and-pessimistic_write-work.md][spring-data-jpa-locking.md]

### Job-queue worker
- Many workers polling the same `task` table -> `SELECT ... FOR UPDATE SKIP LOCKED LIMIT N` via `PESSIMISTIC_WRITE` + `jakarta.persistence.lock.timeout = -2` (SKIP_LOCKED). Without `SKIP LOCKED`, workers serialize on the head row; with it, each worker grabs a disjoint batch [hibernate-locking-patterns-how-do-pessimistic_read-and-pessimistic_write-work.md]

### Cross-aggregate invariant
- Parent aggregate `Repository`, child `Commit` change must invalidate concurrent commits ->
  - Read-mostly workload, conflicts rare -> `OPTIMISTIC_FORCE_INCREMENT` on parent at load
  - High contention, want failure detected immediately -> `PESSIMISTIC_FORCE_INCREMENT` on parent at load [hibernate-locking-patterns-how-does-optimistic_force_increment-lock-mode-work.md][hibernate-locking-patterns-how-does-pessimistic_force_increment-lock-mode-work.md]

### Retry strategy on `OptimisticLockException`
- Is the operation idempotent and conflict rare?
  - Yes -> retry with bounded count (e.g., 3-10) and exponential backoff in a NEW transaction (e.g., `@Retry(times=3, on=OptimisticLockException.class)` via AOP) [optimistic-locking-retry-with-jpa.md]
  - No (user-driven form submission) -> surface a 409 Conflict to the user with a merge UI; do not blindly retry [optimistic-locking-retry-with-jpa.md]
- Is the conflict structural (same hot row constantly)?
  - Yes -> redesign: split aggregate (`Product` -> `ProductStock` + `ProductLiking`) so writers target disjoint version counters [an-entity-modeling-strategy-for-scaling-optimistic-locking.md]

### Split aggregate to scale optimistic locking
- Single `@Version` on a hot entity rejecting >10% of writes? -> Split entity by write-responsibility, one `@Version` per sub-entity, join in reads via `JOIN FETCH` or 2L cache [an-entity-modeling-strategy-for-scaling-optimistic-locking.md]

### Defend against Write Skew across two rows
- Both rows must be consistent and DB is MVCC (PostgreSQL/Oracle non-Serializable)?
  - Cheap, correct -> `SELECT ... FOR UPDATE` both rows in ascending PK order via `LockModeType.PESSIMISTIC_WRITE`
  - High contention, OK with retry -> PostgreSQL `SERIALIZABLE` (SSI) + retry on `SQLState 40001`
  - Single-row OPTIMISTIC `@Version` is NOT sufficient — disjoint writes both succeed [a-beginners-guide-to-read-and-write-skew-phenomena.md][write-skew-2pl-mvcc.md]

### Choose logical vs physical clock for `@Version`
- Single-node RDBMS, predictable monotonicity needed? -> `@Version private short version` (logical clock)
- Distributed system already using HLC/Lamport ids? -> still use a logical counter at the row level; coordinate distributed ordering above [logical-vs-physical-clock-optimistic-locking.md]

### Handle an `OptimisticLockException` at the API edge
- Idempotent server-driven workflow (batch retry job) -> automatic retry with exponential backoff via AOP `@Retry(on = OptimisticLockException.class, times = N)` [optimistic-locking-retry-with-jpa.md]
- User-driven form submission -> return HTTP 409 with the latest entity snapshot; let UI present merge resolution; never auto-retry blind writes [optimistic-locking-retry-with-jpa.md]
- Background event consumer (Kafka, SQS) -> retry with bounded count, then send to a DLQ; surface metric for conflict rate [optimistic-locking-retry-with-jpa.md]

### Pick a database isolation level for the workload
- High write contention on disjoint columns of the same row -> Read Committed + `@OptimisticLocking(type=DIRTY) + @DynamicUpdate`, accepting the detached-entity caveat [how-to-prevent-optimisticlockexception-using-hibernate-versionless-optimistic-locking.md]
- Multi-row invariants -> Repeatable Read or PostgreSQL Serializable (SSI); be ready to retry on 40001 [a-beginners-guide-to-read-and-write-skew-phenomena.md][write-skew-2pl-mvcc.md]
- Queue / worker pool -> Read Committed + `PESSIMISTIC_WRITE` + `SKIP LOCKED` is almost always the right answer [hibernate-locking-patterns-how-do-pessimistic_read-and-pessimistic_write-work.md]

## Anti-patterns: WRONG / CORRECT

### 1. Missing `@Version` on a mutable entity
```java
// WRONG — last write silently wins; lost-update anomaly across HTTP requests
@Entity
public class Product {
    @Id private Long id;
    private int quantity;
}
```
```java
// CORRECT — Hibernate adds version to WHERE, throws OptimisticLockException on stale write
@Entity
public class Product {
    @Id private Long id;
    private int quantity;
    @Version private short version;   // short is enough; see best-way-map-entity-version
}
```
Source: [optimistic-locking-version-property-jpa-hibernate.md]

### 2. `find()` without `LockModeType` when reading-then-writing across aggregates
```java
// WRONG — Alice reads Product, places Order; price engine updates Product concurrently;
// Alice commits with stale price because she did not modify Product.
Product p = em.find(Product.class, id);
em.persist(new OrderLine(p));
```
```java
// CORRECT — explicit OPTIMISTIC ensures Product.version is re-checked before commit
Product p = em.find(Product.class, id, LockModeType.OPTIMISTIC);
em.persist(new OrderLine(p));
```
Source: [hibernate-locking-patterns-how-does-optimistic-lock-mode-work.md]

### 3. Catching `OptimisticLockException` without retry / outside the failed tx
```java
// WRONG — catching inside the same transaction; the Persistence Context is poisoned,
// further operations on `em` are undefined.
@Transactional
public void update(Long id) {
    try {
        Product p = em.find(Product.class, id);
        p.setName("x");
        em.flush();
    } catch (OptimisticLockException e) {
        // swallow and continue with the same em -> corrupt state
    }
}
```
```java
// CORRECT — retry in a NEW transaction after the failed one is rolled back
@Retry(times = 3, on = OptimisticLockException.class)
public Product update(Long id, String name) {
    return tx.execute(status -> {
        Product p = em.find(Product.class, id);
        p.setName(name);
        return p;
    });
}
```
Source: [optimistic-locking-retry-with-jpa.md]

### 4. Long-running `PESSIMISTIC_WRITE` spanning an HTTP request / think-time
```java
// WRONG — holds row lock for seconds; every other request blocks
@Transactional
public Product startEditing(Long id) {
    return em.find(Product.class, id, LockModeType.PESSIMISTIC_WRITE);
    // returned to UI, user thinks for 30s, lock still held
}
```
```java
// CORRECT — read with OPTIMISTIC for the UI, take PESSIMISTIC_WRITE only at submit time
public ProductDto load(Long id) {
    return toDto(em.find(Product.class, id));   // no lock
}
@Transactional
public void save(Long id, int version, ProductDto dto) {
    Product p = em.find(Product.class, id, LockModeType.PESSIMISTIC_WRITE);
    if (p.getVersion() != version) throw new OptimisticLockException();
    apply(p, dto);
}
```
Source: [a-beginners-guide-to-database-locking-and-the-lost-update-phenomena.md][hibernate-locking-patterns-how-do-pessimistic_read-and-pessimistic_write-work.md]

### 5. Not bumping parent version on child-collection change
```java
// WRONG — adding a Commit does not change Repository columns, so its @Version
// is NOT incremented; two concurrent commits both succeed and break invariant.
@Transactional
public void commit(Long repoId, Change change) {
    Repository repo = em.find(Repository.class, repoId);
    Commit c = new Commit(repo); c.add(change);
    em.persist(c);
}
```
```java
// CORRECT — force-increment the parent to serialize concurrent commits
@Transactional
public void commit(Long repoId, Change change) {
    Repository repo = em.find(Repository.class, repoId,
                              LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    Commit c = new Commit(repo); c.add(change);
    em.persist(c);
}
```
Source: [hibernate-locking-patterns-how-does-optimistic_force_increment-lock-mode-work.md][hibernate-collections-optimistic-locking.md]

### 6. Lost update via detached read-then-write (versionless or with merge)
```java
// WRONG — detached Product is merged; Hibernate SELECTs the latest row, applies
// detached fields on top, then writes — Bob's concurrent change is lost.
Product detached = loadInRequest1(id);   // tx closed
detached.setPrice(BigDecimal.ONE);
inRequest2(em -> em.merge(detached));    // overwrites Bob silently
```
```java
// CORRECT — keep a @Version column; merge of stale detached entity throws
// OptimisticLockException at flush
@Entity class Product { @Id Long id; BigDecimal price; @Version short version; }
inRequest2(em -> em.merge(detached));    // throws if version stale
```
Source: [how-to-prevent-optimisticlockexception-using-hibernate-versionless-optimistic-locking.md]

### 7. Bulk JPQL update without versioning
```java
// WRONG — bypasses @Version; any concurrent Alice update is lost
em.createQuery("update Post set status = :s where status = :p")
  .setParameter("s", SPAM).setParameter("p", PENDING)
  .executeUpdate();
```
```java
// CORRECT — increment version in the bulk statement (or use `update versioned`)
em.createQuery("""
    update Post
    set status = :s, version = version + 1
    where status = :p
    """)
  .setParameter("s", SPAM).setParameter("p", PENDING)
  .executeUpdate();
// equivalently:
// em.createQuery("update versioned Post set status = :s where status = :p")
```
Source: [bulk-update-optimistic-locking.md]

### 8. Locking via raw SQL fragment instead of `LockModeType`
```java
// WRONG — non-portable, breaks on SQL Server / Oracle / DB2 dialects
List<Task> tasks = em.createQuery(
    "select t from Task t where t.status = 'NEW' for update skip locked", Task.class)
    .getResultList();
```
```java
// CORRECT — let Hibernate emit dialect-specific syntax
List<Task> tasks = em.createQuery(
    "select t from Task t where t.status = 'NEW'", Task.class)
    .setLockMode(LockModeType.PESSIMISTIC_WRITE)
    .setHint("jakarta.persistence.lock.timeout", LockOptions.SKIP_LOCKED)
    .setMaxResults(batchSize)
    .getResultList();
```
Source: [spring-data-jpa-locking.md][hibernate-locking-patterns-how-do-pessimistic_read-and-pessimistic_write-work.md]

### 9. Inconsistent lock acquisition order causing deadlocks
```java
// WRONG — Thread A locks accountX then accountY; Thread B locks Y then X -> deadlock
em.find(Account.class, fromId, LockModeType.PESSIMISTIC_WRITE);
em.find(Account.class, toId,   LockModeType.PESSIMISTIC_WRITE);
```
```java
// CORRECT — always acquire in ascending id order
long a = Math.min(fromId, toId), b = Math.max(fromId, toId);
em.find(Account.class, a, LockModeType.PESSIMISTIC_WRITE);
em.find(Account.class, b, LockModeType.PESSIMISTIC_WRITE);
```
Source: [database-deadlock.md][lock-processing-logic-by-customer.md]

### 10. Write skew across two rows under MVCC
```java
// WRONG — Post and PostDetails must stay in sync, but under PostgreSQL Repeatable Read
// both Alice and Bob read both rows, then disjointly update one each — invariant broken
@Transactional
public void renameAndAudit(Long postId, String title, String user) {
    Post p = em.find(Post.class, postId);
    PostDetails d = em.find(PostDetails.class, postId);
    if (!p.getTitle().equals(title)) p.setTitle(title);
    if (!user.equals(d.getUpdatedBy())) d.setUpdatedBy(user);
}
```
```java
// CORRECT — lock both rows pessimistically in deterministic order before reading state
@Transactional
public void renameAndAudit(Long postId, String title, String user) {
    Post p = em.find(Post.class, postId, LockModeType.PESSIMISTIC_WRITE);
    PostDetails d = em.find(PostDetails.class, postId, LockModeType.PESSIMISTIC_WRITE);
    p.setTitle(title);
    d.setUpdatedBy(user);
}
```
Source: [a-beginners-guide-to-read-and-write-skew-phenomena.md][write-skew-2pl-mvcc.md]

### 11. Versionless `@OptimisticLocking(type=DIRTY)` with detached entities
```java
// WRONG — entity has no @Version, sessions span requests, merge() is used
@Entity
@OptimisticLocking(type = OptimisticLockType.DIRTY)
@DynamicUpdate
public class Product { @Id Long id; BigDecimal price; }
// Request 1: load Product, return DTO; Request 2: merge stale Product -> Hibernate
// SELECTs the latest row, applies stale field on top -> Bob's update silently lost.
```
```java
// CORRECT — use a surrogate @Version column, which survives detach/merge cycles
@Entity
public class Product {
    @Id Long id;
    BigDecimal price;
    @Version short version;
}
```
Source: [how-to-prevent-optimisticlockexception-using-hibernate-versionless-optimistic-locking.md]

### 12. Holding application-level (in-JVM) locks across an external API call
```java
// WRONG — JVM lock held while calling slow web service; other customers blocked,
// risk of deadlock if the API callback re-enters the same lock
synchronized (customerLock) {
    paymentGateway.charge(customer); // 2-second HTTP call
    repo.save(customer);
}
```
```java
// CORRECT — do external work outside the lock, only mutate DB state under lock
ChargeResult r = paymentGateway.charge(customer);   // no lock
synchronized (customerLock) {
    repo.save(customer.applyCharge(r));
}
```
Source: [lock-processing-logic-by-customer.md]

### 13. Spring Data `@Lock` placed on a derived query that does not need it / forgotten where it does
```java
// WRONG — derived finder used in a write path with no lock
public interface PostRepo extends JpaRepository<Post, Long> {
    Optional<Post> findById(Long id);   // used by debit() -> stale read
}
```
```java
// CORRECT — annotate the write-path finder with @Lock, leave read-only paths unlocked
public interface PostRepo extends JpaRepository<Post, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Post p where p.id = :id")
    Optional<Post> findByIdForUpdate(@Param("id") Long id);
}
```
Source: [spring-data-jpa-locking.md]

## Performance Pitfalls

- Hot-row contention: a single `@Version` on a popular entity rejects most concurrent writes; split the aggregate by write-responsibility (e.g., `Product` -> `ProductStock` + `ProductLiking`) so each writer targets a different version counter [an-entity-modeling-strategy-for-scaling-optimistic-locking.md]
- Lock escalation / coarse-grain locks: HSQLDB and SQL Server can promote row locks to page/table locks under load; keep transactions short and select narrow result sets to avoid promoting `FOR UPDATE` ranges [hibernate-locking-patterns-how-does-pessimistic_force_increment-lock-mode-work.md]
- Deadlock storms under 2PL Serializable / SQL Server: switch to Read Committed Snapshot or MVCC databases (PostgreSQL, Oracle) for workloads with frequent multi-row updates [database-deadlock.md][a-beginners-guide-to-database-locking-and-the-lost-update-phenomena.md]
- NOWAIT exception storms: setting `jakarta.persistence.lock.timeout = 0` makes every contended lock fail immediately — use it only with bounded retry, otherwise users see random 500s [hibernate-locking-patterns-how-do-pessimistic_read-and-pessimistic_write-work.md]
- MVCC bloat: long-running read transactions on PostgreSQL pin old row versions, ballooning table size; cap transaction duration and avoid holding sessions open across HTTP requests [a-beginners-guide-to-database-locking-and-the-lost-update-phenomena.md]
- `OPTIMISTIC` lock mode TOCTOU race: the version recheck happens just before commit, so another transaction can still slip in between the SELECT version and the COMMIT; combine with `OPTIMISTIC_FORCE_INCREMENT` (or a pessimistic lock upgrade) when the read must be strictly serialized with writers [hibernate-locking-patterns-how-does-optimistic-lock-mode-work.md]
- `CascadeType.LOCK` on managed entities is a no-op unless `LockOptions.setScope(true)` is set, and even then it only reattaches detached children — do not rely on it to lock the whole graph automatically [hibernate-cascadetype-lock-gotchas.md]
- Versionless `@OptimisticLocking(type = DIRTY)` requires `@DynamicUpdate` and BREAKS for detached entities (merge/update re-loads the row, losing the original snapshot); only use it for short-lived sessions with no detachment [how-to-prevent-optimisticlockexception-using-hibernate-versionless-optimistic-locking.md]
- Bulk UPDATE without `versioned` keyword silently bypasses optimistic locking on tens of thousands of rows — a single negligent statement can wipe an hour of concurrent edits [bulk-update-optimistic-locking.md]
- `PESSIMISTIC_READ` falls back to `PESSIMISTIC_WRITE` on Oracle/MSSQL; do not assume readers can proceed concurrently across all databases — test on the target RDBMS [spring-data-jpa-locking.md]
- SQL Server foreign-key UPDATEs acquire a shared key lock on the parent record (even under RCSI); high-throughput child-row updates therefore serialize on the parent and can deadlock with parent updates — exclude unchanged FK columns from the UPDATE with `@DynamicUpdate` to avoid the issue [sql-server-foreign-key-locking.md]
- 2PL Serializable on SQL Server / MySQL holds read locks until commit, growing the wait graph quadratically with transaction length — keep transactions short and never reach out to a slow API while holding DB locks [2pl-two-phase-locking.md][lock-processing-logic-by-customer.md]
- Long-running MySQL statements can be blocked indefinitely by metadata locks acquired by DDL or by an `XA PREPARE` left dangling — monitor `performance_schema.metadata_locks` and end XA transactions promptly [mysql-metadata-locking-and-database-transaction-ending.md]
- SQL Server deadlock trace flags (1204 / 1222) log victims to the error log; enable them in staging to prove which queries form the cycle before applying lock-order fixes [sql-server-deadlock-trace-flags.md]
- Column-level pessimistic locks (e.g., YugabyteDB) only conflict between transactions that touch the same column, drastically reducing contention on wide rows — but Hibernate's `@DynamicUpdate` is required to issue narrow UPDATEs that take advantage of it [yugabytedb-column-level-locking.md]
- Phantom reads under Read Committed / Repeatable Read can break "no-duplicate" invariants enforced by application code — either move the check into a unique index or escalate to Serializable / explicit predicate locking [phantom-read.md][race-condition.md]
- MVCC databases never block readers, but a long-running read transaction prevents `VACUUM` from reclaiming dead tuples, causing index bloat and slowdowns hours later — keep read transactions bounded and prefer separate read-only transactions for reports [how-does-mvcc-multi-version-concurrency-control-work.md]
- `INSERT ... ON CONFLICT DO UPDATE` (or MERGE) under MVCC still requires write locks; do not assume upserts are free of contention on hot rows like counters [how-does-database-pessimistic-locking-interact-with-insert-update-and-delete-sql-statements.md]
- `CascadeType.LOCK` does NOT propagate locks to children of a managed parent — only on detached reattach with `LockOptions.setScope(true)`; if you need the whole graph locked, issue per-entity lock requests or a single `JOIN` query with `setLockMode` [hibernate-cascadetype-lock-gotchas.md]
- Owned `@OneToMany` (non-`mappedBy`) collection mutations bump the parent's `@Version`; inverse (`mappedBy`) collections do NOT — choose the collection mapping deliberately so concurrent child additions either conflict or scale, per your invariant [hibernate-collections-optimistic-locking.md]
- Logical clocks use a small integer; `short` suffices for almost all rows (~32k changes), reducing index footprint over `long` for high-cardinality tables [optimistic-locking-version-property-jpa-hibernate.md]
- Striped in-JVM locks (e.g., `ConcurrentHashMap<CustomerId, ReentrantLock>`) only protect a single node — for multi-instance deployments use DB-level advisory locks (`pg_advisory_xact_lock`) or distributed lock services (Zookeeper, Redis Redlock) instead [lock-processing-logic-by-customer.md]
- Lock-upgrade race: starting with `LockModeType.OPTIMISTIC` and later upgrading to `PESSIMISTIC_WRITE` works, but the upgrade still re-reads the row; do not assume the snapshot is unchanged between the two [hibernate-locking-patterns-how-does-optimistic-lock-mode-work.md][how-to-fix-optimistic-locking-race-conditions-with-pessimistic-locking.md]
- Pessimistic lock + cascade trap: `CascadeType.ALL` includes `LOCK`, but only fires for detached entity reattach with `setScope(true)`; new developers often expect parent locks to cascade automatically during a managed-entity save and are surprised when children remain unlocked [hibernate-cascadetype-lock-gotchas.md]
- `OPTIMISTIC_FORCE_INCREMENT` runs the increment as a deferred `BeforeTransactionCompletionProcess`, meaning two concurrent transactions can both pass the load-time check yet collide at commit — the failure is detected, but the wasted work upstream is not refunded; switch to `PESSIMISTIC_FORCE_INCREMENT` if upstream work is expensive [hibernate-locking-patterns-how-does-optimistic_force_increment-lock-mode-work.md][hibernate-locking-patterns-how-does-pessimistic_force_increment-lock-mode-work.md]
- Bulk delete with `@Version` works the same as bulk update — if you omit `version = version + 1` (or `delete versioned`), concurrent edits made between SELECT and DELETE can be silently discarded [bulk-update-optimistic-locking.md]
- The first level of cache (Persistence Context) guarantees session-level repeatable reads — a second `em.find()` of the same id in the same transaction returns the cached instance, masking concurrent DB changes; use `em.refresh()` to force a re-read [a-beginners-guide-to-java-persistence-locking.md]
- Read-modify-write patterns at Read Committed are race conditions in disguise — the compare in your Java code is not atomic with the UPDATE, so even single-row mutations need `@Version` or `SELECT FOR UPDATE` to be safe [race-condition.md]
- SQL Server deadlock graphs captured via trace flag 1222 reveal the exact lock-resource ids and statement texts in the cycle, giving you the data needed to reorder lock acquisition in code [sql-server-deadlock-trace-flags.md]
- A `NOWAIT` lock request (`jakarta.persistence.lock.timeout = 0`) is appropriate when a stale view is acceptable but waiting is not — e.g., dashboard refresh; pair with a fallback to cached data on `LockTimeoutException` [hibernate-locking-patterns-how-do-pessimistic_read-and-pessimistic_write-work.md]
- Foreign-key indexes are mandatory under SQL Server's FK-locking model: without a covering index, the engine must scan the child table to validate the FK and escalates locks dramatically [sql-server-foreign-key-locking.md]
- Under MySQL Serializable, every SELECT acquires a shared lock; mixing Serializable with high concurrency frequently deadlocks — keep the isolation level at Repeatable Read and use explicit locks where Write Skew is possible [write-skew-2pl-mvcc.md]
- Validate concurrency code with deterministic two-thread tests (`CountDownLatch`, `executeAsync`, `executeSync`) that interleave SELECT and UPDATE between Alice and Bob — every lock-mode article in this cluster uses the same pattern, and the same harness should live in your test suite [hibernate-locking-patterns-how-does-optimistic-lock-mode-work.md][hibernate-locking-patterns-how-does-optimistic_force_increment-lock-mode-work.md]
- Treat any `StaleObjectStateException`/`OptimisticLockException` rate above ~1% in production as a design smell — split the aggregate, change isolation, or fall back to pessimistic locking, rather than masking it with infinite retry [an-entity-modeling-strategy-for-scaling-optimistic-locking.md][optimistic-locking-retry-with-jpa.md]

## Citations

- `[2pl-two-phase-locking.md]` — How 2PL acquires/releases locks for Strict Serializability; lock compatibility matrix
- `[a-beginners-guide-to-database-locking-and-the-lost-update-phenomena.md]` — Lost-update phenomenon, Read Committed limits, FOR UPDATE, optimistic locking
- `[a-beginners-guide-to-java-persistence-locking.md]` — Implicit vs explicit, physical vs logical locks in JPA
- `[a-beginners-guide-to-read-and-write-skew-phenomena.md]` — Read/Write Skew matrix across Oracle/SQL Server/PostgreSQL/MySQL isolation levels
- `[an-entity-modeling-strategy-for-scaling-optimistic-locking.md]` — Splitting aggregates by write-responsibility to scale `@Version` contention
- `[bulk-update-optimistic-locking.md]` — Versioning bulk JPQL UPDATE / `update versioned`
- `[database-deadlock.md]` — How deadlocks arise and which DBs choose the victim how
- `[hibernate-cascadetype-lock-gotchas.md]` — `CascadeType.LOCK`, `LockOptions.setScope(true)`, detached reattach semantics
- `[hibernate-collections-optimistic-locking.md]` — Owned vs inverse collections and parent-version bumping rules
- `[hibernate-locking-patterns-how-do-pessimistic_read-and-pessimistic_write-work.md]` — `FOR SHARE` / `FOR UPDATE`, SKIP LOCKED, NOWAIT, dialect map
- `[hibernate-locking-patterns-how-does-optimistic-lock-mode-work.md]` — Explicit `LockModeType.OPTIMISTIC` and its TOCTOU race
- `[hibernate-locking-patterns-how-does-optimistic_force_increment-lock-mode-work.md]` — Bumping parent version on unmodified entity for cross-aggregate consistency
- `[hibernate-locking-patterns-how-does-pessimistic_force_increment-lock-mode-work.md]` — Immediate increment + physical lock for fail-fast aggregate locking
- `[how-to-prevent-optimisticlockexception-using-hibernate-versionless-optimistic-locking.md]` — `@OptimisticLocking(type=DIRTY/ALL)` and detached-entity hazards
- `[lock-processing-logic-by-customer.md]` — Application-level striped locking by customer id; deadlock-prevention lock ordering
- `[optimistic-locking-retry-with-jpa.md]` — AOP-based `@Retry` for `OptimisticLockException` outside the failed transaction
- `[optimistic-locking-version-property-jpa-hibernate.md]` — `@Version` mechanics, WHERE-clause filtering, DELETE versioning
- `[how-does-database-pessimistic-locking-interact-with-insert-update-and-delete-sql-statements.md]` — Predicate locks, lock interactions with DML
- `[how-to-fix-optimistic-locking-race-conditions-with-pessimistic-locking.md]` — Optimistic-to-pessimistic upgrade pattern to close TOCTOU window
- `[how-does-mvcc-multi-version-concurrency-control-work.md]` — PostgreSQL x_min/x_max, row visibility, VACUUM implications
- `[logical-vs-physical-clock-optimistic-locking.md]` — Why logical counters beat timestamps for `@Version`
- `[mysql-metadata-locking-and-database-transaction-ending.md]` — MySQL metadata locks, XA transaction hazards
- `[phantom-read.md]` — Phantom reads, predicate locks, when Serializable is required
- `[race-condition.md]` — Application-level race-condition examples and prevention
- `[spring-data-jpa-locking.md]` — `@Lock` annotation, `LockModeType` in `JpaRepository`, dialect translation
- `[sql-server-deadlock-trace-flags.md]` — Trace flags 1204/1222 for capturing deadlock graphs in SQL Server
- `[sql-server-foreign-key-locking.md]` — FK shared lock on parent under RCSI; avoid by excluding FK columns from UPDATE
- `[write-skew-2pl-mvcc.md]` — Write skew under MVCC vs 2PL; MySQL Serializable employing shared locks
- `[yugabytedb-column-level-locking.md]` — Column-level pessimistic locks; pairing with `@DynamicUpdate`
