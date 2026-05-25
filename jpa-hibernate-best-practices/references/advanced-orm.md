# Advanced ORM Topics

## Core Principles

- Pick a multitenancy strategy based on isolation/ops trade-offs: catalog-per-tenant for DBs that don't distinguish catalog from schema (MySQL, MariaDB), schema-per-tenant for PostgreSQL, discriminator-column for low-isolation/shared-table tenants. Wire it through `hibernate.multiTenancy=DATABASE|SCHEMA`, a `MultiTenantConnectionProvider`, and a `CurrentTenantIdentifierResolver` backed by `ThreadLocal` or Spring `RequestScope`. [database-multitenancy.md, hibernate-database-catalog-multitenancy.md]
- Prefer Hibernate 6.4+ `@SoftDelete` over hand-rolled `@SQLDelete` + `@Where` + `@Loader`. `@SoftDelete` auto-appends `deleted = false` to entity loads, fetches, and join-table reads; combine with `@NotFound(action = NotFoundAction.EXCEPTION)` on lazy `@ManyToOne` whose parent may be soft-deleted. [hibernate-softdelete-annotation.md, the-best-way-to-soft-delete-with-hibernate.md]
- For pre-6.4 soft delete, declare `@SQLDelete`, `@Where(clause = "deleted = false")`, and `@Loader(namedQuery = ...)` together — `@Where` covers collections and entity queries, `@Loader` covers direct `find()`. Most RDBMS eliminate the duplicate `deleted = false` predicate during parsing. [the-best-way-to-soft-delete-with-hibernate.md]
- Mark read-only domain objects with `@Immutable`. Hibernate skips snapshot tracking, omits the entity from dirty checking, and (with `hibernate.query.immutable_entity_update_query_handling_mode=exception`) blocks JPQL/Criteria bulk updates against them. [immutable-entity-jpa-hibernate.md]
- Treat `LazyInitializationException` as a code smell pointing at missing fetch planning, not a configuration bug. Fix it with `JOIN FETCH` for read-write entities or DTO projections for read-only views — never with OSIV or `hibernate.enable_lazy_load_no_trans`. [the-best-way-to-handle-the-lazyinitializationexception.md, the-hibernate-enable_lazy_load_no_trans-anti-pattern.md]
- Implement `equals`/`hashCode` based on stable identity: `@NaturalId` business key when one exists; otherwise database-generated `@Id` with `hashCode` returning `getClass().hashCode()` (constant) and `equals` doing `id != null && id.equals(other.id)`. This survives all entity state transitions including `merge` and `getReference`. [the-best-way-to-implement-equals-hashcode-and-tostring-with-jpa-and-hibernate.md]
- Use `entityManager.getReference(Parent.class, id)` (not `find`) when setting a `@ManyToOne`/`@OneToOne` FK on a new child — it returns an uninitialized proxy and skips the parent SELECT. Use `find` only when you actually need the parent's attributes. [entitymanager-find-getreference-jpa.md, how-does-a-jpa-proxy-work-and-how-to-unproxy-it-with-hibernate.md]
- Apply `@DynamicUpdate` only when entities have many columns and most updates touch few of them. Default static UPDATE statements are pre-cached and usually faster. [how-to-update-only-a-subset-of-entity-attributes-using-jpa-and-hibernate.md]
- Enable bytecode enhancement (build-time, via `hibernate-enhance-maven-plugin`) for dirty tracking, lazy attribute loading, and bidirectional association management — but only when default reflection-based dirty checking is provably the bottleneck. The snapshot is still kept in the Persistence Context. [the-anatomy-of-hibernate-dirty-checking.md, the-best-way-to-implement-equals-hashcode-and-tostring-with-jpa-and-hibernate.md]
- Register a `StatementInspector` via `hibernate.session_factory.statement_inspector` to log/observe every SQL Hibernate emits; combine with `hibernate.use_sql_comments=true` to tag each statement with its originating Hibernate operation, then strip the comment in the inspector before it hits the wire so it doesn't poison the statement cache. [hibernate-statementinspector.md]
- Customize identifier translation via `hibernate.physical_naming_strategy` (e.g., `CamelCaseToSnakeCaseNamingStrategy`) rather than annotating every `@Column(name = ...)` by hand. [hibernate-physical-naming-strategy.md]
- Never run `hibernate.hbm2ddl.auto=update` or `create` in production. Use `validate` in CI/integration tests against the same migration scripts (Flyway/Liquibase) that built the prod schema; use `create-only`/`drop` only as a one-shot bootstrap for generating initial DDL. [hibernate-hbm2ddl-auto-schema.md]
- Let Hibernate 6 resolve the Dialect automatically from `DatabaseMetaData` — omit `hibernate.dialect`. Hardcoding `MySQL57Dialect` breaks transparently when you upgrade to MySQL 8 or Aurora 3. [hibernate-dialect.md]
- Bootstrap JPA programmatically via a `PersistenceUnitInfo` implementation + `PersistenceProvider#createContainerEntityManagerFactory` when you want zero `persistence.xml`, full control over `Integrator` registration, or per-test isolated `EntityManagerFactory` instances. [how-to-bootstrap-jpa-programmatically-without-the-persistence-xml-configuration-file.md]
- Hook into the Persistence Context lifecycle with Hibernate event listeners (`PostInsertEventListener`, `PostUpdateEventListener`, `PreDeleteEventListener`) registered through an `Integrator` and `hibernate.integrator_provider` — use this for CDC-style replication, audit trails, or table mirroring while staying inside the same JDBC transaction. [hibernate-event-listeners.md]
- Map recursive self-referencing FKs as `@ManyToOne(fetch = LAZY) Category parent`. Fetch depth-N hierarchies with stacked `left join fetch c1.parent c2 ...` in JPQL, or escape to a native recursive CTE for unbounded trees. [recursive-associations-jpa-hibernate.md]
- Map two entities to one table (`@Entity @Table(name="post")` + `@Entity @Table(name="post")`) — one full, one summary — to avoid loading large columns (JSON, BLOB) or to dodge the secondary SELECT that bidirectional `@OneToOne` always emits. Share columns via a `@MappedSuperclass` base. [multiple-entities-on-same-table.md, map-multiple-jpa-entities-one-table-hibernate.md]
- Run integration tests against the same RDBMS engine you use in production via Testcontainers — not H2/HSQLDB. SQL-specific features, native queries, and stored procedures only behave correctly on the real engine. [testcontainers-database-integration-testing.md]
- Enable schema validation (`hibernate.hbm2ddl.auto=validate`) during application bootstrap and CI tests to catch drift between entity mappings and the migrated schema before it hits production. [how-to-fix-wrong-column-type-encountered-schema-validation-errors-with-jpa-and-hibernate.md, hibernate-hbm2ddl-auto-schema.md]
- Use the JPQL `on conflict(...) do update` clause (Hibernate 6.5+) for portable upserts — Hibernate translates it to `ON CONFLICT DO UPDATE` (PostgreSQL), `ON DUPLICATE KEY UPDATE` (MySQL), or `MERGE` (Oracle/SQL Server). [hibernate-on-conflict-do-clause.md]

## Decision Trees

### Multitenancy approach
```
Need full data isolation + separate backups per tenant?
+-- YES, DB doesn't distinguish catalog/schema (MySQL/MariaDB)
|     -> MultiTenancyStrategy.DATABASE (catalog-per-tenant)
|        + MultiTenantConnectionProvider with one DataSource per tenant
+-- YES, DB has true schemas (PostgreSQL)
|     -> MultiTenancyStrategy.SCHEMA (schema-per-tenant)
|        + DataSource per tenant, set currentSchema
+-- NO, hundreds/thousands of small tenants, shared infra OK
      -> Discriminator column (tenant_id) + @TenantId
         + Hibernate filter or @Where for automatic filtering
         (Watch: large shared tables, single-tenant noisy-neighbor risk)
```
[database-multitenancy.md, hibernate-database-catalog-multitenancy.md]

### Soft delete strategy
```
Hibernate >= 6.4?
+-- YES -> @SoftDelete on entity + @SoftDelete on @ManyToMany join collections
|          + @NotFound(EXCEPTION) on lazy parents
+-- NO  -> @SQLDelete(sql="UPDATE ... SET deleted=true WHERE id=?")
           + @Where(clause="deleted = false")
           + @Loader(namedQuery="findXxxById") for direct find()

DB has native temporal/flashback (Oracle Flashback, SQL Server temporal tables)?
+-- YES -> Use it; skip app-level soft delete entirely
```
[hibernate-softdelete-annotation.md, the-best-way-to-soft-delete-with-hibernate.md, soft-delete-jpa-version.md]

### Hit LazyInitializationException
```
Are you in a UI render layer accessing the entity after tx commit?
+-- YES -> You're treating entities as DTOs. Switch to a DTO projection
|          (constructor expression / Tuple / interface projection).
+-- NO  -> The service layer forgot to initialize the association.
           + Read-write path: add JOIN FETCH in the JPQL query
           + Single ToOne nav: switch to entityManager.getReference()
             if you only need FK
           + Multiple collections needed: use @EntityGraph or
             secondary Hibernate.initialize() calls
           NEVER: enable OSIV, NEVER set enable_lazy_load_no_trans=true
```
[the-best-way-to-handle-the-lazyinitializationexception.md, the-hibernate-enable_lazy_load_no_trans-anti-pattern.md]

### Hibernate event listeners or aspects?
```
Need cross-cutting behavior that must run inside the same JDBC tx
as the entity change?
+-- YES, depends on persistence lifecycle (insert/update/delete order,
|        flush order, FK creation) -> Hibernate event listeners
|        (PostInsert/PostUpdate/PreDelete + Integrator)
+-- NO, business-level concern, no persistence ordering -> Spring AOP
        @Around / @AfterReturning on the service method
```
[hibernate-event-listeners.md]

### Choose Dialect
```
Hibernate 6+?
+-- YES -> DO NOT set hibernate.dialect. Let DialectResolutionInfo
|          discover server + driver version automatically.
+-- NO (5.x) -> Set the exact dialect matching server version
               (e.g. MySQL8Dialect, PostgreSQL10Dialect).
               Plan a Dialect review before any DB major upgrade.
```
[hibernate-dialect.md]

### EntityManager bootstrap
```
Spring Boot / Spring Framework? -> Use LocalContainerEntityManagerFactoryBean
                                   in @Configuration. Skip persistence.xml.
Jakarta EE container?           -> @PersistenceUnit / @PersistenceContext
                                   with a persistence.xml.
Library/test/CLI tool?          -> Implement PersistenceUnitInfo,
                                   call PersistenceProvider
                                   .createContainerEntityManagerFactory
                                   (programmatic, no XML).
```
[how-to-bootstrap-jpa-programmatically-without-the-persistence-xml-configuration-file.md]

### Find vs getReference
```
Are you going to read any attribute of the parent (title, status, ...)?
+-- YES -> entityManager.find(Parent.class, id)
+-- NO, only setting it as a FK on a child entity
        -> entityManager.getReference(Parent.class, id)
           (returns proxy, no SELECT, just FK value used at flush)
```
[entitymanager-find-getreference-jpa.md, how-does-a-jpa-proxy-work-and-how-to-unproxy-it-with-hibernate.md]

## Anti-patterns: WRONG / CORRECT

### 1. enable_lazy_load_no_trans
WRONG — silently opens a temp Session per lazy nav, each in its own tx:
```properties
hibernate.enable_lazy_load_no_trans=true
```
```java
List<PostComment> comments = repo.findByReview("Excellent!"); // tx closed
for (PostComment c : comments) {
    log.info(c.getPost().getTitle()); // hidden SELECT in new tx, per row
}
```
CORRECT — fetch what you need inside the original tx:
```java
@Transactional(readOnly = true)
List<PostComment> findByReviewWithPost(String review) {
    return em.createQuery("""
        select pc from PostComment pc
        join fetch pc.post
        where pc.review = :review
        """, PostComment.class)
        .setParameter("review", review)
        .getResultList();
}
```
[the-hibernate-enable_lazy_load_no_trans-anti-pattern.md, the-best-way-to-handle-the-lazyinitializationexception.md]

### 2. equals/hashCode using mutable field
WRONG — `hashCode` changes when title is edited; entity disappears from `HashSet`:
```java
@Override public boolean equals(Object o) {
    if (!(o instanceof Post p)) return false;
    return Objects.equals(title, p.title);
}
@Override public int hashCode() { return Objects.hash(title); }
```
CORRECT — stable identity across all state transitions:
```java
@Override public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Post other)) return false;
    return id != null && id.equals(other.getId());
}
@Override public int hashCode() { return getClass().hashCode(); }
```
[the-best-way-to-implement-equals-hashcode-and-tostring-with-jpa-and-hibernate.md]

### 3. Comparing proxies with `==` or `.getClass()`
WRONG — proxy is a subclass, never equal to the real entity by reference:
```java
Post real    = em.find(Post.class, 1L);
Post proxy   = em.getReference(Post.class, 1L);
if (real == proxy) { ... }                  // false
if (real.getClass() == proxy.getClass()) { } // false (different subclasses)
```
CORRECT — unwrap proxy first, or rely on `equals()` (with `instanceof`, not `getClass()`):
```java
Object unwrapped = Hibernate.unproxy(proxy);
assert real.equals(unwrapped); // uses id-based equals
```
[how-does-a-jpa-proxy-work-and-how-to-unproxy-it-with-hibernate.md, the-best-way-to-implement-equals-hashcode-and-tostring-with-jpa-and-hibernate.md]

### 4. @Immutable but still calling JPQL update
WRONG — silently mutates a read-only entity (only WARN by default):
```java
@Entity @Immutable
class Event { @Id Long id; String eventValue; }

em.createQuery("update Event set eventValue = :v where id = :id")
  .setParameter("v", "10").setParameter("id", 1L).executeUpdate();
// HHH000487 WARN, but the UPDATE still runs
```
CORRECT — fail loudly so the mistake is caught in tests:
```properties
hibernate.query.immutable_entity_update_query_handling_mode=exception
```
[immutable-entity-jpa-hibernate.md]

### 5. Flag-based soft delete polluting every query
WRONG — every JPQL query in the codebase must remember the predicate:
```java
@Entity class Tag { @Id Long id; boolean deleted; }

// callers everywhere:
em.createQuery("select t from Tag t where t.deleted = false", Tag.class);
em.createQuery("select t from Tag t where t.name = :n and t.deleted = false");
```
CORRECT — let the mapping enforce it; queries stay clean:
```java
@Entity @Table(name = "tag") @SoftDelete  // Hibernate 6.4+
class Tag { @Id Long id; @NaturalId String name; }

// or pre-6.4:
@Entity @SQLDelete(sql = "UPDATE tag SET deleted=true WHERE id=?")
@Where(clause = "deleted = false")
@Loader(namedQuery = "findTagById")
@NamedQuery(name = "findTagById",
    query = "select t from Tag t where t.id = ?1 and t.deleted = false")
class Tag { ... }
```
[hibernate-softdelete-annotation.md, the-best-way-to-soft-delete-with-hibernate.md]

### 6. hbm2ddl=update in production
WRONG — irreversible schema drift, no migration history, no rollback:
```properties
spring.jpa.hibernate.ddl-auto=update
```
CORRECT — Flyway/Liquibase migrations, Hibernate only validates:
```properties
spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```
[hibernate-hbm2ddl-auto-schema.md]

### 7. Hardcoded Dialect that breaks on DB upgrade
WRONG — pinning to MySQL 5.7 silently degrades when prod moves to MySQL 8:
```properties
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQL57Dialect
```
CORRECT (Hibernate 6+) — let auto-resolution pick the right one:
```properties
# nothing — DialectResolutionInfo handles MySQL 5.x, 8.x, Aurora 2/3, MariaDB
```
[hibernate-dialect.md]

### 8. Using find() to set a FK
WRONG — extra SELECT just to read an id we already have:
```java
Post post = em.find(Post.class, 1L);  // SELECT * FROM post WHERE id=1
PostComment c = new PostComment();
c.setPost(post);
em.persist(c);
```
CORRECT — proxy carries the FK without a SELECT:
```java
Post post = em.getReference(Post.class, 1L); // no SELECT
PostComment c = new PostComment();
c.setPost(post);
em.persist(c);                                // single INSERT
```
[entitymanager-find-getreference-jpa.md]

### 9. Ignoring schema validation errors
WRONG — silently boot with mismatched mapping; runtime corruption later:
```java
// hbm2ddl=none, no validation, mapping says BIGINT, schema is NUMERIC(19,0)
@Id @GeneratedValue(strategy = IDENTITY) Long id;
```
CORRECT — turn on validate, declare the exact column type:
```properties
hibernate.hbm2ddl.auto=validate
```
```java
@Id @GeneratedValue(strategy = IDENTITY)
@Column(columnDefinition = "NUMERIC(19,0)")
private Long id;
```
[how-to-fix-wrong-column-type-encountered-schema-validation-errors-with-jpa-and-hibernate.md]

### 10. Single fat entity loading a JSON blob every read
WRONG — every list view drags the multi-KB `properties` JSON over the wire:
```java
@Entity @Table(name = "book")
class Book {
    @Id Long id; String title; String author;
    @Type(JsonType.class) @Column(columnDefinition="jsonb") String properties;
}
List<Book> page = repo.findAll(PageRequest.of(0, 50)); // 50 JSON blobs
```
CORRECT — split the table into summary + full entities sharing one DB table:
```java
@MappedSuperclass abstract class BaseBook { @Id Long id; String title; String author; }

@Entity @Table(name = "book")
class BookSummary extends BaseBook { }

@Entity @Table(name = "book") @DynamicUpdate
class Book extends BaseBook {
    @Type(JsonType.class) @Column(columnDefinition="jsonb") String properties;
}

// list view:
repo.findAllSummaries(PageRequest.of(0, 50)); // no JSON
// detail view:
em.find(Book.class, id);                       // full row incl. JSON
```
[map-multiple-jpa-entities-one-table-hibernate.md, multiple-entities-on-same-table.md]

### 11. No StatementInspector, no insight into slow queries
WRONG — production fires N+1 queries with no idea where they originate:
```properties
# nothing logged beyond the SQL itself
spring.jpa.show-sql=true
```
CORRECT — comment + inspector pinpoint origin, strip before execution:
```properties
hibernate.use_sql_comments=true
hibernate.session_factory.statement_inspector=com.acme.SqlCommentStatementInspector
```
```java
public class SqlCommentStatementInspector implements StatementInspector {
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/\\s*");
    @Override public String inspect(String sql) {
        log.debug("SQL: {}", sql);                  // keeps the comment for log
        return COMMENT.matcher(sql).replaceAll(""); // strips before DB sees it
    }
}
```
[hibernate-statementinspector.md]

### 12. Manual JPQL upsert with check-then-insert
WRONG — race condition between the SELECT and the INSERT/UPDATE:
```java
Book b = em.createQuery("select b from Book b where b.id = :id", Book.class)
    .setParameter("id", id).getResultList().stream().findFirst().orElse(null);
if (b == null) em.persist(new Book(id, title, isbn));
else { b.setTitle(title); b.setIsbn(isbn); }
```
CORRECT — single atomic statement, portable across PG/MySQL/Oracle:
```java
em.createQuery("""
    insert into Book (id, title, isbn) values (:id, :title, :isbn)
    on conflict(id) do update
       set title = excluded.title, isbn = excluded.isbn
    """)
  .setParameter("id", id).setParameter("title", title).setParameter("isbn", isbn)
  .executeUpdate();
```
[hibernate-on-conflict-do-clause.md]

## Performance Pitfalls

- Default reflection-based dirty checking copies a snapshot of every managed entity at load time; Persistence Contexts holding thousands of entities pay 2x memory and O(n*p) CPU on every flush. Cap context size or enable bytecode dirty tracking. [the-anatomy-of-hibernate-dirty-checking.md]
- `@DynamicUpdate` recompiles the UPDATE SQL on every flush; for hot, small-table writes this is slower than static cached SQL. Use only when the entity is wide and updates are narrow. [how-to-update-only-a-subset-of-entity-attributes-using-jpa-and-hibernate.md]
- Bidirectional `@OneToOne` always issues a secondary SELECT for the child even with `FetchType.LAZY`, because Hibernate must know whether to set the field to null or to a proxy. Map a second summary entity to the same table to avoid the round trip when the child isn't needed. [multiple-entities-on-same-table.md]
- `find()` always hits the first-level cache, second-level cache, or DB; `getReference()` never queries until you touch a non-id attribute. Using `find` to set a FK doubles the statement count on every insert path. [entitymanager-find-getreference-jpa.md]
- Soft-delete `@Where` clauses get appended to every entity query and join — the predicate cost adds up on cold caches and unindexed `deleted` columns. Add a partial index `where deleted = false` for hot tables. [the-best-way-to-soft-delete-with-hibernate.md]
- Event listeners fire on every matching state transition; an inefficient listener (e.g., extra native query per insert) silently doubles write latency. Use `setFlushMode(MANUAL)` inside the listener to avoid recursive flushes. [hibernate-event-listeners.md]
- Recursive JPQL with N stacked `left join fetch parent` joins is bounded — fine for known depth, terrible if the hierarchy can grow. Drop to a native recursive CTE once depth is unbounded. [recursive-associations-jpa-hibernate.md]
- Bytecode enhancement does not eliminate the Persistence Context snapshot; it changes how change detection happens, not memory footprint. Keep the context short anyway. [how-to-enable-bytecode-enhancement-dirty-checking-in-hibernate.md via the-best-way-to-implement-equals-hashcode-and-tostring-with-jpa-and-hibernate.md]
- `hibernate.use_sql_comments=true` sends the comment to the DB on every statement — it interferes with statement caching (PostgreSQL plan cache, MySQL prepared statement cache) and adds network bytes. Strip in a `StatementInspector` before execution. [hibernate-statementinspector.md]
- Multitenancy with one `DataSource` per tenant multiplies your connection pool footprint: `tenants * pool_size` connections at idle. Watch `max_connections` on the DB server and pool more aggressively per-tenant. [hibernate-database-catalog-multitenancy.md]
- `hbm2ddl=update` re-runs introspection at every boot and silently issues ALTERs against prod — both a startup-time tax and a data-loss vector. [hibernate-hbm2ddl-auto-schema.md]
- Hardcoded `Dialect` blocks Hibernate from picking up version-aware optimizations (e.g., `VARCHAR` max-length, native `MERGE`, window functions). After a DB upgrade you keep running pre-upgrade SQL. [hibernate-dialect.md]
- Testing against H2 hides production-only behavior: identity column types, NULLS ordering, JSON operators, isolation defaults. Bugs surface only after deploy. Use Testcontainers to catch them in CI. [testcontainers-database-integration-testing.md]

## Citations

- [database-multitenancy.md] — Beginner's guide to catalog/schema/discriminator tenant isolation trade-offs.
- [entitymanager-find-getreference-jpa.md] — find() vs getReference(): when each emits SQL and why getReference saves a SELECT for FK setup.
- [hibernate-database-catalog-multitenancy.md] — End-to-end MySQL catalog-per-tenant: MultiTenancyStrategy.DATABASE, MultiTenantConnectionProvider, CurrentTenantIdentifierResolver.
- [hibernate-dialect.md] — Why Hibernate 6 auto-resolves the Dialect from DatabaseMetaData and you should stop setting hibernate.dialect.
- [hibernate-event-listeners.md] — Registering PostInsert/PostUpdate/PreDelete listeners through an Integrator for CDC-style table mirroring.
- [hibernate-hbm2ddl-auto-schema.md] — All hbm2ddl.auto values explained; why update is unsafe and validate is the only prod-suitable option.
- [hibernate-on-conflict-do-clause.md] — JPQL on conflict(id) do update clause; per-dialect translation to ON CONFLICT / ON DUPLICATE KEY / MERGE.
- [hibernate-physical-naming-strategy.md] — PhysicalNamingStrategy interface and CamelCaseToSnakeCaseNamingStrategy for default identifier translation.
- [hibernate-softdelete-annotation.md] — Native Hibernate 6.4 @SoftDelete on entities, join collections, and lazy parents with @NotFound.
- [hibernate-statementinspector.md] — StatementInspector to log, modify, and strip SQL comments before execution.
- [how-does-a-jpa-proxy-work-and-how-to-unproxy-it-with-hibernate.md] — How getReference returns a proxy, when navigation triggers SELECT, and Hibernate.unproxy.
- [how-to-bootstrap-jpa-programmatically-without-the-persistence-xml-configuration-file.md] — Implementing PersistenceUnitInfo to build EntityManagerFactory without persistence.xml.
- [how-to-fix-wrong-column-type-encountered-schema-validation-errors-with-jpa-and-hibernate.md] — Diagnosing schema-validation mismatches and pinning columnDefinition.
- [how-to-update-only-a-subset-of-entity-attributes-using-jpa-and-hibernate.md] — @DynamicUpdate, updatable=false, @Transient — controlling which columns flush.
- [immutable-entity-jpa-hibernate.md] — @Immutable mapping plus immutable_entity_update_query_handling_mode=exception to block JPQL/Criteria bulk updates.
- [map-multiple-jpa-entities-one-table-hibernate.md] — Mapping summary + full entities (sharing @MappedSuperclass) to one table to avoid loading large columns.
- [multiple-entities-on-same-table.md] — Same pattern applied to dodge the bidirectional @OneToOne secondary SELECT.
- [recursive-associations-jpa-hibernate.md] — Self-referencing @ManyToOne; fetching depth-N hierarchies with stacked JOIN FETCH vs recursive CTE.
- [soft-delete-jpa-version.md] — Soft delete combined with @Version optimistic locking.
- [testcontainers-database-integration-testing.md] — Replacing H2/HSQLDB with Testcontainers; AbstractContainerDataSourceProvider pattern.
- [the-anatomy-of-hibernate-dirty-checking.md] — Default snapshot+compare flow, O(n*p) cost, build-time vs runtime bytecode weaving options.
- [the-best-way-to-handle-the-lazyinitializationexception.md] — LIE root causes; JOIN FETCH for read-write paths, DTO projection for read-only.
- [the-best-way-to-implement-equals-hashcode-and-tostring-with-jpa-and-hibernate.md] — @NaturalId vs database-generated id strategies; getClass().hashCode() trick.
- [the-best-way-to-soft-delete-with-hibernate.md] — Pre-6.4 @SQLDelete + @Where + @Loader combination across one-to-one, one-to-many, many-to-many.
- [the-hibernate-enable_lazy_load_no_trans-anti-pattern.md] — Why enable_lazy_load_no_trans opens temp Sessions/transactions per lazy nav and destroys throughput.
