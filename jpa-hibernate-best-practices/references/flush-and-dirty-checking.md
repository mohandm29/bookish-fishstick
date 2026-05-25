# Flush, Dirty Checking and Entity State

## Core Principles

- JPA defines four entity states. `TRANSIENT` (new) — a freshly constructed object never associated with a Persistence Context and not mapped to any row; `MANAGED` (persistent) — attached to the current Persistence Context and tracked field-by-field; `DETACHED` — previously managed but the Persistence Context has been closed or the entity was evicted, so subsequent changes are no longer flushed; `REMOVED` — scheduled for `DELETE` at the next flush. Drive transitions via `persist`, `merge`, `remove`, `refresh`, `detach`. [`a-beginners-guide-to-jpa-hibernate-entity-state-transitions.md`]
- Hibernate exists as a write-behind cache: managed changes accumulate in the Persistence Context (first-level cache) and are translated to SQL only at flush-time, never on every setter call. Treat the Persistence Context as a buffer between in-memory state transitions and the database. [`a-beginners-guide-to-jpahibernate-flush-strategies.md`]
- `FlushModeType.AUTO` (JPA default): flush occurs before commit and before any query (JPQL, Criteria, native SQL). Hibernate's native `FlushMode.AUTO` is weaker — it flushes "sometimes" (only when the query's table-space overlaps pending DML). `COMMIT`: flush only at commit. `ALWAYS` (Hibernate-only): flush before every query. `MANUAL` (Hibernate-only): never auto-flush — caller must invoke `flush()`. `NEVER` is deprecated; use `MANUAL`. [`a-beginners-guide-to-jpahibernate-flush-strategies.md`, `how-do-jpa-and-hibernate-define-the-auto-flush-mode.md`]
- Under JPA-bootstrapped Hibernate, native SQL queries do trigger auto-flush. Under native Hibernate bootstrap (`SessionFactory` directly), native SQL queries do **not** flush unless you call `query.setFlushMode(FlushMode.ALWAYS)` or call `addSynchronizedEntityClass` / `addSynchronizedQuerySpace` to tell Hibernate which tables the SQL touches. Since Hibernate 5.2, JPA-bootstrapped sessions behave per JPA. [`how-does-the-auto-flush-work-in-jpa-and-hibernate.md`, `how-do-jpa-and-hibernate-define-the-auto-flush-mode.md`]
- Even for JPQL/HQL, Hibernate auto-flush is opportunistic: it inspects the query's referenced tables against pending DML and skips the flush if there is no overlap. Database triggers or DB-side cascades are invisible to this optimization, so auto-flush can still produce inconsistencies even for JPQL. [`how-does-the-auto-flush-work-in-jpa-and-hibernate.md`]
- Dirty checking is field-by-field: at flush, Hibernate compares the current entity state against the loaded snapshot (the hydrated state captured when the entity was loaded). Every managed entity is scanned on every flush, whether you changed it or not. Bytecode enhancement (`hibernate-enhance-maven-plugin` with `enableDirtyTracking`) replaces snapshot scanning with attribute-level change tracking, eliminating the O(N×fields) cost. [`a-beginners-guide-to-jpa-hibernate-entity-state-transitions.md`, `jpa-persist-merge-hibernate-save-update-saveorupdate.md`]
- Use `persist` for `TRANSIENT` entities (returns void, attaches the passed instance to the context). Use `merge` for `DETACHED` entities — it fetches the row, copies detached state onto a freshly-loaded managed instance, and returns that managed copy; the original argument remains detached. Hibernate's legacy `save`, `update`, `saveOrUpdate` all dispatch a `SaveOrUpdateEvent`; they predate JPA and are deprecated in Hibernate 6. [`jpa-persist-and-merge.md`, `jpa-persist-merge-hibernate-save-update-saveorupdate.md`]
- Cascading propagates entity-state events (`PERSIST`, `MERGE`, `REMOVE`, etc.) from parent to child, but it does not change flush-time ordering. The cascaded operation is enqueued like any other; the `ActionQueue` then plays operations back in fixed order at flush. [`hibernate-facts-knowing-flush-operations-order-matters.md`]
- Hibernate's flush ordering is fixed by `ActionQueue`, not insertion order: `OrphanRemovalAction` → `AbstractEntityInsertAction` → `EntityUpdateAction` → `QueuedOperationCollectionAction` → `CollectionRemoveAction` → `CollectionUpdateAction` → `CollectionRecreateAction` → `EntityDeleteAction`. Inserts run **before** deletes — so `remove(old); persist(new)` with a shared unique key blows up on the unique-constraint violation unless you flush in between or update in place. [`hibernate-facts-knowing-flush-operations-order-matters.md`]
- Dirty-check behavior is customizable: `@DynamicUpdate` makes Hibernate emit `UPDATE` statements containing only changed columns (at the cost of disabling prepared-statement caching for that entity); a custom `org.hibernate.Interceptor#findDirty` lets you override which properties Hibernate considers dirty; `@SelectBeforeUpdate` issues a `SELECT` before update on a detached entity so Hibernate can skip the `UPDATE` when nothing actually changed. [`jpa-persist-merge-hibernate-save-update-saveorupdate.md`]
- Modifying a managed entity already requires no `save`/`merge` call — the change is propagated automatically at flush. Calling `save`/`merge` on a managed entity is a no-op that still fires `MergeEvent`/`SaveOrUpdateEvent`, copies the hydrated state into a new array, and cascades to children for nothing. [`jpa-persist-and-merge.md`]
- `IDENTITY` identifier generation forces an `INSERT` inside `persist()` (the DB must produce the PK), which disables JDBC insert batching. `SEQUENCE` and `TABLE` allow `persist()` to defer the `INSERT` to flush-time and enable batching; prefer `SEQUENCE` over `TABLE` (the table strategy serializes on a row lock and burns connection-pool capacity). [`jpa-persist-and-merge.md`]

## Decision Trees

### "I have an entity to write — which method?"
```
Is the entity TRANSIENT (never had a DB row)?
├── Yes → persist()              [returns void; attaches argument]
└── No
    ├── Is it currently MANAGED (loaded in this Persistence Context)?
    │   └── Yes → do NOTHING; mutate fields, dirty checking flushes UPDATE
    └── Is it DETACHED (loaded in a prior tx / received from client)?
        ├── Need the returned managed reference? → merge()  [SELECT + UPDATE]
        ├── Batching many detached entities? → session.update() / Session.merge() avoids extra SELECT per entity
        └── Removing? → entityManager.find(id) then remove(), NOT remove(detached)
```
Source: `jpa-persist-and-merge.md`, `jpa-persist-merge-hibernate-save-update-saveorupdate.md`, `how-to-optimize-the-merge-operation-using-update-while-batching-with-jpa-and-hibernate.md`

### "Which FlushMode should this Persistence Context use?"
```
Are you running native SQL queries that depend on pending writes (read-your-own-writes)?
├── Yes
│   ├── JPA-bootstrapped → AUTO is safe
│   └── Native Hibernate bootstrap → AUTO is NOT safe; use ALWAYS, or addSynchronizedEntityClass on the query
└── No
    ├── Long-running conversation/multi-request edit + manual control of when changes ship?
    │   └── MANUAL (caller must invoke flush(); pair with explicit em.flush() before commit)
    └── Read-mostly screen, want to suppress incidental UPDATEs from JPQL traversal?
        └── COMMIT (flush only at tx commit; queries see stale DB state for own writes)
```
Source: `a-beginners-guide-to-jpahibernate-flush-strategies.md`, `how-do-jpa-and-hibernate-define-the-auto-flush-mode.md`, `how-does-the-auto-flush-work-in-jpa-and-hibernate.md`

### "When does AUTO flush actually fire?"
```
About to execute a query?
├── JPQL/HQL/Criteria → Hibernate parses the query, gets its table-space, compares to pending DML
│   ├── Overlap → FLUSH
│   └── No overlap → SKIP (still risky if DB triggers/cascades reference the unflushed tables)
├── Native SQL via JPA bootstrap → FLUSH (JPA spec mandates)
├── Native SQL via native Hibernate bootstrap → SKIP unless setFlushMode(ALWAYS) or addSynchronizedQuerySpace
└── About to commit() → FLUSH unconditionally (unless MANUAL and you forgot — silent data loss)
```
Source: `how-does-the-auto-flush-work-in-jpa-and-hibernate.md`, `how-do-jpa-and-hibernate-define-the-auto-flush-mode.md`

### "Dirty checking is hot in my profile — what do I change?"
```
Persistence Context routinely holds > a few hundred entities?
├── Yes
│   ├── Enable bytecode enhancement with enableDirtyTracking=true  [eliminates snapshot diff]
│   └── Shorten the PC: paginate, clear() between batches, use StatelessSession for bulk
└── No
    ├── A specific UPDATE column-set matters (huge LOB column unchanged)?
    │   └── @DynamicUpdate on that entity only; measure — it disables PS cache reuse
    └── Need custom dirty semantics (e.g. semantic equals not reference)?
        └── Implement Interceptor#findDirty and register on SessionFactoryBuilder
```
Source: `jpa-persist-merge-hibernate-save-update-saveorupdate.md`

### "Remove-then-reinsert with the same unique key fails — what now?"
```
Are old.id and new.id different but they share a unique column (e.g. NaturalId/slug)?
├── Yes
│   ├── Best: load old via NaturalId, mutate fields in place (no remove, no insert)
│   └── Compromise: em.remove(old); em.flush(); em.persist(new);   [manual flush is a code smell]
└── No (you really do need both rows during the tx) → reconsider the unique constraint or use deferred constraints
```
Source: `hibernate-facts-knowing-flush-operations-order-matters.md`

## Anti-patterns: WRONG / CORRECT

### 1. Calling `save`/`merge` on an already-managed entity ("redundant save" anti-pattern)
[`jpa-persist-and-merge.md`]

WRONG:
```java
@Transactional
public void savePostTitle(Long postId, String title) {
    Post post = postRepository.findOne(postId);
    post.setTitle(title);
    postRepository.save(post);   // fires MergeEvent, recopies hydrated state, cascades for nothing
}
```

CORRECT:
```java
@Transactional
public void savePostTitle(Long postId, String title) {
    Post post = postRepository.findOne(postId);
    post.setTitle(title);        // dirty checking emits UPDATE at flush — that is the whole point
}
```

### 2. Manual `flush()` in the middle of a transaction to "make sure it sticks"
[`hibernate-facts-knowing-flush-operations-order-matters.md`, `a-beginners-guide-to-jpahibernate-flush-strategies.md`]

WRONG:
```java
@Transactional
public void rotateSlug(Long oldId, Long newId, String slug) {
    Post old = em.find(Post.class, oldId);
    em.remove(old);
    em.flush();                            // band-aid for flush-ordering pain
    Post fresh = new Post(newId, slug);
    em.persist(fresh);
}
```

CORRECT:
```java
@Transactional
public void rotateSlug(Long oldId, String newTitle) {
    Post post = em.unwrap(Session.class)
        .bySimpleNaturalId(Post.class)
        .load("high-performance-java-persistence");   // resolve by business key
    post.setTitle(newTitle);                          // update in place; no delete + insert
}
```

### 3. Assuming `flush()` equals `commit()`
[`a-beginners-guide-to-jpahibernate-flush-strategies.md`]

WRONG:
```java
em.persist(order);
em.flush();
// caller assumes other sessions can now see the order — they cannot until commit
return ResponseEntity.ok(order.getId());   // and the surrounding tx may still roll back
```

CORRECT:
```java
em.persist(order);
// Let the @Transactional boundary commit. Other sessions see writes only after commit,
// regardless of how many flushes occurred mid-transaction.
return ResponseEntity.ok(order.getId());
```

### 4. Using deprecated Hibernate `save()` instead of JPA `persist()`
[`jpa-persist-merge-hibernate-save-update-saveorupdate.md`]

WRONG:
```java
Session session = em.unwrap(Session.class);
Long id = (Long) session.save(book);   // deprecated in Hibernate 6; fires SaveOrUpdateEvent
```

CORRECT:
```java
em.persist(book);
Long id = book.getId();                // identifier populated by sequence/identity at persist time
```

### 5. Mutating a `DETACHED` entity and expecting auto-update
[`a-beginners-guide-to-jpa-hibernate-entity-state-transitions.md`, `jpa-persist-and-merge.md`]

WRONG:
```java
Post post = postRepository.findById(1L).get();   // tx closes here in Spring without @Transactional
post.setTitle("Updated");                        // detached — no Persistence Context tracking this
postRepository.save(post);                       // hopes Spring/Hibernate will re-find and update
// but if the entity never had a version column and the implementation chose persist(), you get an EntityExistsException or duplicate insert
```

CORRECT:
```java
@Transactional
public void updateTitle(Long id, String title) {
    Post post = em.find(Post.class, id);   // managed inside this tx
    post.setTitle(title);                  // dirty checking flushes UPDATE at commit
}
// OR, when truly working from a detached DTO/long conversation:
@Transactional
public Post applyClientEdit(Post detached) {
    return em.merge(detached);             // returns the MANAGED copy; ignore the detached arg afterwards
}
```

### 6. `FlushMode.MANUAL` without an explicit `flush()` before commit
[`a-beginners-guide-to-jpahibernate-flush-strategies.md`]

WRONG:
```java
Session s = em.unwrap(Session.class);
s.setHibernateFlushMode(FlushMode.MANUAL);
Post p = new Post("x");
em.persist(p);
// transaction commits with no flush — INSERT never executes, data silently lost
```

CORRECT:
```java
Session s = em.unwrap(Session.class);
s.setHibernateFlushMode(FlushMode.MANUAL);
em.persist(p);
// ... long conversation logic ...
em.flush();        // explicit, before commit boundary
```

### 7. Native query under `FlushMode.COMMIT` (or native Hibernate bootstrap) returns stale results
[`how-does-the-auto-flush-work-in-jpa-and-hibernate.md`, `how-do-jpa-and-hibernate-define-the-auto-flush-mode.md`]

WRONG:
```java
Session session = em.unwrap(Session.class);   // native Hibernate bootstrap
Product p = new Product("Blue");
session.persist(p);
Number count = (Number) session
    .createNativeQuery("SELECT COUNT(*) FROM product")
    .getSingleResult();
// count == 0 — the INSERT was never flushed before the native query ran
```

CORRECT:
```java
Number count = (Number) session
    .createNativeQuery("SELECT COUNT(*) FROM product")
    .unwrap(NativeQuery.class)
    .addSynchronizedEntityClass(Product.class)   // tells Hibernate the SQL touches Product's table
    .getSingleResult();
// or: query.setFlushMode(FlushMode.ALWAYS);
// or: bootstrap via JPA so AUTO covers native queries automatically
```

### 8. `@DynamicUpdate` slapped on every entity "for performance"
[`jpa-persist-merge-hibernate-save-update-saveorupdate.md`]

WRONG:
```java
@Entity @DynamicUpdate   // copy-pasted to every entity
public class Customer { /* 6 small columns */ }
// every UPDATE now generates a different SQL string depending on which columns changed,
// destroying the JDBC PreparedStatement cache hit rate and the DB's plan cache
```

CORRECT:
```java
@Entity   // default static UPDATE for narrow rows — reused PS, reused plan
public class Customer { /* 6 small columns */ }

@Entity @DynamicUpdate   // only where the savings dominate: wide rows with large rarely-touched columns
public class Document {
    @Lob private byte[] payload;   // 50 MB column we don't want in every UPDATE
    private String title;
}
```

### 9. Flush-ordering trap: insert-before-delete on a shared unique key
[`hibernate-facts-knowing-flush-operations-order-matters.md`]

WRONG:
```java
Post old = em.find(Post.class, 1L);
em.remove(old);
Post replacement = new Post(2L, "new title", "same-slug");
em.persist(replacement);
// flush() runs INSERT before DELETE → SQLState 23505 unique constraint violation
```

CORRECT (preferred — update in place):
```java
Post post = em.unwrap(Session.class)
    .bySimpleNaturalId(Post.class)
    .load("same-slug");
post.setTitle("new title");   // no delete, no insert, indexes untouched
```

### 10. Overwriting a `@OneToMany` collection on merge
[`merge-entity-collections-jpa-hibernate.md`]

WRONG:
```java
@Transactional
public void replaceComments(Post detached, List<PostComment> fresh) {
    detached.setComments(fresh);          // replaces the PersistentBag reference
    em.merge(detached);                    // Hibernate cannot diff its own collection — orphan removal misbehaves
}
```

CORRECT:
```java
@Transactional
public void replaceComments(Long postId, List<PostComment> fresh) {
    Post managed = em.find(Post.class, postId);
    managed.getComments().removeIf(c -> fresh.stream().noneMatch(f -> f.getId() != null && f.getId().equals(c.getId())));
    for (PostComment f : fresh) {
        if (f.getId() == null) managed.addComment(f);   // helper keeps both sides in sync
        else managed.getComments().stream()
                .filter(c -> c.getId().equals(f.getId()))
                .findFirst()
                .ifPresent(c -> c.setReview(f.getReview()));
    }
}
```

## Performance Pitfalls

- Dirty checking is O(entities × fields) on every flush. With auto-flush, every JPQL query in a long method triggers another full scan. Without bytecode enhancement, a Persistence Context holding 10,000 entities does 10,000 hydrated-state array comparisons per flush. Enable `hibernate-enhance-maven-plugin` with `enableDirtyTracking=true`, or split the work into smaller transactions / use `EntityManager.clear()` between chunks. [`jpa-persist-merge-hibernate-save-update-saveorupdate.md`, `a-beginners-guide-to-jpa-hibernate-entity-state-transitions.md`]
- Unbounded Persistence Contexts are an outage waiting to happen. Long-running session / open-session-in-view + a hydration-heavy endpoint = OOM. Cap the lifetime to a service-method transaction, and prefer `StatelessSession` for ETL/import loops where dirty tracking and first-level cache provide no value. [`a-beginners-guide-to-jpahibernate-flush-strategies.md`]
- `flush()` inside a loop defeats JDBC batching by forcing each chunk through the wire individually. Configure `hibernate.jdbc.batch_size`, `hibernate.order_inserts=true`, `hibernate.order_updates=true`, `hibernate.jdbc.batch_versioned_data=true`, then let the natural commit-time flush ship one batched round-trip per statement type. Manual flush belongs only at deliberate `persist`/`clear` chunk boundaries during bulk loads. [`how-to-optimize-the-merge-operation-using-update-while-batching-with-jpa-and-hibernate.md`, `hibernate-facts-knowing-flush-operations-order-matters.md`]
- `merge()` always issues a `SELECT` before the `UPDATE` (to load the managed copy onto which it copies state). For a batch reattaching 1,000 detached entities, that is 1,000 extra round-trips. Use Hibernate's `Session.update()` (or, in Hibernate 6+, `Session.merge()` with custom strategy) to skip the read when you already trust the detached state — the trade-off is losing optimistic concurrency without a `@Version` column. [`how-to-optimize-the-merge-operation-using-update-while-batching-with-jpa-and-hibernate.md`, `jpa-persist-merge-hibernate-save-update-saveorupdate.md`]
- `IDENTITY` PKs disable insert batching entirely because Hibernate must round-trip per row to get the generated id. Switch to `SEQUENCE` (with `allocationSize` matching your batch size) for any entity inserted in bulk. [`jpa-persist-and-merge.md`]
- Calling `save()`/`merge()` on managed entities is not just a no-op — it allocates a fresh hydrated-state array, fires a `MergeEvent`, and if `CascadeType.MERGE` is in play, walks every child. Hot paths with deep object graphs pay this cost on every request. Remove the call. [`jpa-persist-and-merge.md`]
- `@DynamicUpdate` produces a different SQL string per change-set, killing the prepared-statement cache and forcing the database to re-plan. Use only on entities where the avoided columns are genuinely expensive (LOBs, very wide rows) and measure before/after. [`jpa-persist-merge-hibernate-save-update-saveorupdate.md`]
- Mixing entity types (Insert A, Insert B, Insert A) without `hibernate.order_inserts=true` produces interleaved statements that JDBC cannot batch. Enable ordering so Hibernate groups statements by entity type before flushing the `ActionQueue`. [`hibernate-facts-knowing-flush-operations-order-matters.md`]
- Native SQL under native-Hibernate bootstrap with `AUTO` silently skips flushes. The bug surfaces as "the row I just inserted is missing from the report I just ran" — and only in production where bootstrap differs from tests. Either standardize on JPA bootstrap or always set `FlushMode.ALWAYS` / `addSynchronizedEntityClass` on native queries. [`how-does-the-auto-flush-work-in-jpa-and-hibernate.md`, `how-do-jpa-and-hibernate-define-the-auto-flush-mode.md`]
- Replacing a managed `@OneToMany` collection (`setComments(new ArrayList<>(...))`) detaches Hibernate's `PersistentBag`, breaking orphan-removal diffing and forcing a delete-then-reinsert of every child — every flush. Mutate the existing collection in place. [`merge-entity-collections-jpa-hibernate.md`]
- Open-Session-In-View + lazy associations + serialization to JSON triggers N+1 lazy-loads, each potentially auto-flushing if you persisted/updated earlier in the request. Disable OSIV in services that write, or assemble DTOs inside the transaction. [`a-beginners-guide-to-jpahibernate-flush-strategies.md`]

## Citations

- `a-beginners-guide-to-jpa-hibernate-entity-state-transitions.md` — Defines the four entity states (transient/managed/detached/removed) and the `EntityManager`/`Session` methods that transition between them.
- `a-beginners-guide-to-jpahibernate-flush-strategies.md` — Explains transactional write-behind, flush-before-query, and the `FlushMode` enum (AUTO/COMMIT/ALWAYS/MANUAL/NEVER) for both JPA and Hibernate.
- `hibernate-facts-knowing-flush-operations-order-matters.md` — Documents the fixed `ActionQueue` ordering at flush (inserts before deletes) and the remove-then-insert anti-pattern on shared unique keys.
- `how-do-jpa-and-hibernate-define-the-auto-flush-mode.md` — Contrasts JPA's strict AUTO (flushes for any query, including native) with Hibernate native's opportunistic AUTO and notes the Hibernate 5.2 alignment when bootstrapped via JPA.
- `how-does-the-auto-flush-work-in-jpa-and-hibernate.md` — Walks through Hibernate's table-space-overlap heuristic for JPQL auto-flush, native-SQL inconsistencies, and how to opt back in with `FlushMode.ALWAYS` or `addSynchronizedEntityClass`/`addSynchronizedQuerySpace`.
- `how-to-optimize-the-merge-operation-using-update-while-batching-with-jpa-and-hibernate.md` — Shows that `merge` issues a per-entity SELECT and recommends Hibernate `update()` for batching detached entities, combined with `order_inserts`/`order_updates`/`batch_size`.
- `jpa-persist-and-merge.md` — Defines correct usage of `persist` (transient) vs `merge` (detached), explains IDENTITY/SEQUENCE/TABLE generator effects on batching, and identifies the redundant-save anti-pattern with the `MergeEvent`/`DefaultMergeEventListener` cost analysis.
- `jpa-persist-merge-hibernate-save-update-saveorupdate.md` — Compares JPA `persist`/`merge` with Hibernate legacy `save`/`update`/`saveOrUpdate`, and introduces `@SelectBeforeUpdate`, `@DynamicUpdate`, and dirty-check customization.
- `merge-entity-collections-jpa-hibernate.md` — Explains why replacing a `@OneToMany` collection during merge is an anti-pattern and how to mutate the managed collection in place with orphan removal.

