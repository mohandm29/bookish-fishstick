# Spring Data JPA Best Practices

## Core Principles

- Disable Open-Session-in-View by setting `spring.jpa.open-in-view=false` in `application.properties`; the default `true` keeps the persistence context open during view rendering, hiding LazyInitializationException bugs and holding connections far longer than needed [`spring-boot-application-properties.md`].
- Annotate the service class with `@Transactional(readOnly = true)` and override at the method level with `@Transactional` for write operations; this routes read-only flows through optimized Hibernate `FlushMode.MANUAL` and allows JDBC drivers (PostgreSQL, MySQL) to skip TX bookkeeping [`spring-transactional-annotation.md`].
- Place `@Transactional` in the Service layer, never in the Web/Controller layer and never in the Repository layer; the Repository must inherit the active transaction propagated from the Service [`spring-transactional-annotation.md`].
- Prefer the custom `BaseJpaRepository` / `HibernateRepository` from Hypersistence Utils over the default `JpaRepository` so `save`, `saveAll`, and `findAll` are removed from the contract and developers must consciously choose `persist`, `merge`, or `update` [`best-spring-data-jparepository.md`, `spring-data-base-repository.md`].
- Never call `save()` on a managed entity loaded inside the same `@Transactional` boundary; dirty checking flushes the change automatically and the extra `merge` only triggers a wasted `MergeEvent` and cascade traversal [`best-spring-data-jparepository.md`].
- Use derived query methods (`findByTitle`) for 1-2 simple predicates; switch to `@Query` JPQL/HQL for joins, projections, or anything with more than two parameters; switch to `Specification` when the predicate set is built dynamically from optional filters [`spring-data-query-methods.md`, `spring-data-jpa-specification.md`].
- For bulk inserts/updates from a list, prefer `persistAll` / `mergeAll` / `updateAll` (Hypersistence Utils) which set the JDBC batch size on the Session; never iterate calling `repository.save(entity)` per element [`best-spring-data-jparepository.md`].
- Project to DTOs (interface projections, class records, or `@Query` constructor expressions) when the caller only needs a few columns; never return managed entities across a `@Transactional` boundary to a controller [`records-spring-data-jpa.md`, `spring-data-query-methods.md`].
- Index every column referenced in `Pageable` `Sort` and in `where` clauses of derived methods; an unindexed sort column forces a full table sort on every page request [`keyset-pagination-spring.md`].
- Use `existsBy...` derived methods (or `select 1 ... limit 1`) instead of `findBy...().isPresent()` when you only need a yes/no answer; Spring Data emits a much cheaper `SELECT 1` query [`spring-data-exists-query.md`].
- Stream large result sets with `Stream<T>` repository methods inside an open transaction and `try-with-resources`, or use `WindowIterator` with keyset pagination; never load the whole table into a `List` [`spring-data-jpa-stream.md`, `spring-data-windowiterator.md`].
- Ban `findAll()` on transactional entities by deprecating it in your `HibernateRepository` so the IDE warns on every call; `findAll` is virtually never the right answer in production code [`spring-data-findall-anti-pattern.md`, `best-spring-data-jparepository.md`].

## Decision Trees

### Tree 1: Choosing a Query Mechanism

```
Need to query data?
|
+-- Single, fixed predicate on 1-2 fields?
|    +-- YES: Derived query method  findByTitleAndStatus(...)
|             [spring-data-query-methods.md]
|
+-- Joins, fetch joins, aggregation, or projection?
|    +-- YES: @Query("select ... from ... join fetch ...")
|             [spring-data-query-methods.md]
|
+-- Predicates assembled dynamically from optional filters?
|    +-- YES: Specification + JpaSpecificationExecutor
|             Use JPA Metamodel (PostComment_.status), not strings
|             [spring-data-jpa-specification.md]
|
+-- Caller passes a probe entity (search-by-example)?
|    +-- YES: QueryByExampleExecutor.findBy(Example.of(probe))
|             [spring-data-query-by-example.md]
|
+-- Vendor-specific SQL, hints, or recursive CTE?
     +-- YES: @Query(nativeQuery = true) + DTO mapping
              [spring-data-query-methods.md]
```

### Tree 2: Read-only vs Read-write Service Method

```
Method only SELECTs?
|
+-- YES: @Transactional(readOnly = true)
|        Return DTO / record, not managed entity
|        FlushMode.MANUAL is set automatically
|        [spring-transactional-annotation.md, records-spring-data-jpa.md]
|
+-- NO (INSERT/UPDATE/DELETE):
     +-- Mostly reads, a few writes? Class-level readOnly=true,
     |   method-level @Transactional override on writers
     |   [spring-transactional-annotation.md]
     +-- Requires strict isolation (financial)? Add isolation=SERIALIZABLE
         [spring-transactional-annotation.md]
```

### Tree 3: Pagination Strategy

```
Need to page through results?
|
+-- Small offset, UI table jump-to-page?
|    +-- YES: Pageable offset pagination  PageRequest.of(page, size, Sort.by(...))
|             Cost rises with page number; only use for small N
|             [spring-data-query-methods.md]
|
+-- Sequential scan, infinite scroll, batch job, deep pagination?
|    +-- YES: Keyset pagination
|             where (created_on, id) < (:lastCreatedOn, :lastId)
|             order by created_on desc, id desc
|             Use Hypersistence Utils WindowIterator for streaming
|             [keyset-pagination-spring.md, spring-data-windowiterator.md]
|
+-- @OneToMany with JOIN FETCH and Pageable?
     +-- NEVER: Hibernate emits HHH000104 warning and paginates in memory
                Split into two queries: page IDs first, then fetch children
                [spring-data-jpa-multiplebagfetchexception.md]
```

### Tree 4: Bulk Modification

```
Mutating many rows?
|
+-- Same column update across N rows by predicate?
|    +-- YES: @Modifying(clearAutomatically = true, flushAutomatically = true)
|             @Query("update Post p set p.status = :s where p.publishedOn < :d")
|             Must clear context or stale entities linger
|             [spring-data-query-methods.md]
|
+-- Inserting N new entities from a collection?
|    +-- YES: repository.persistAll(list)  (Hypersistence)
|             Configure spring.jpa.properties.hibernate.jdbc.batch_size=30
|             Configure spring.jpa.properties.hibernate.order_inserts=true
|             [best-spring-data-jparepository.md, spring-boot-application-properties.md]
|
+-- Merging N detached entities (e.g. import job)?
     +-- YES: repository.mergeAll(list) or updateAll(list)
              [best-spring-data-jparepository.md]
```

## Anti-patterns: WRONG / CORRECT

### 1. Open-in-View enabled

WRONG (application.properties)
```properties
# default in Spring Boot — leaves persistence context open during view rendering
# spring.jpa.open-in-view=true
```

CORRECT
```properties
spring.jpa.open-in-view=false
```
Source: [`spring-boot-application-properties.md`]. Open-in-View masks LazyInitializationException bugs in development and holds JDBC connections for the entire HTTP response lifetime.

### 2. Redundant save() on a managed entity

WRONG
```java
@Transactional
public void updateTitle(Long postId, String title) {
    Post post = postRepository.findById(postId).orElseThrow();
    post.setTitle(title);
    postRepository.save(post);           // triggers wasteful merge cascade
}
```

CORRECT
```java
@Transactional
public void updateTitle(Long postId, String title) {
    Post post = postRepository.findById(postId).orElseThrow();
    post.setTitle(title);                // dirty checking + auto flush
}
```
Source: [`best-spring-data-jparepository.md`]. The `merge` call inside `save` fires a `MergeEvent` and cascades it across associations for zero benefit.

### 3. save() in a loop without batching

WRONG
```java
@Transactional
public void importPosts(List<Post> posts) {
    for (Post p : posts) {
        postRepository.save(p);          // no batch — one INSERT per row
    }
}
```

CORRECT
```java
@Transactional
public void importPosts(List<Post> posts) {
    postRepository.persistAll(posts);    // HibernateRepository — sets batch size
}
```
Plus `application.properties`:
```properties
spring.jpa.properties.hibernate.jdbc.batch_size=30
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
```
Source: [`best-spring-data-jparepository.md`, `spring-boot-application-properties.md`].

### 4. findAll() then filter in Java

WRONG
```java
List<String> titles = postRepository.findAll().stream()
    .filter(p -> p.getTags().stream()
                  .map(Tag::getName).anyMatch(matchingTags::contains))
    .map(Post::getTitle)
    .toList();                           // N+1 + full table load
```

CORRECT
```java
@Query("""
    select p.title from Post p
    where exists (
       select 1 from p.tags t where t.name in :tags
    )
    order by p.id
    """)
List<String> findTitlesByTagNames(@Param("tags") List<String> tags);
```
Source: [`spring-data-findall-anti-pattern.md`].

### 5. Missing @Transactional(readOnly = true) on query service

WRONG
```java
@Service
public class PostQueryService {
    public List<PostDto> latest() {
        return postRepository.findTop20ByOrderByCreatedOnDesc()
            .stream().map(PostDto::from).toList();
    }
}
```

CORRECT
```java
@Service
@Transactional(readOnly = true)
public class PostQueryService {
    public List<PostDto> latest() {
        return postRepository.findTop20ByOrderByCreatedOnDescAsDto();
    }
}
```
Source: [`spring-transactional-annotation.md`]. `readOnly=true` switches Hibernate to `FlushMode.MANUAL`, skips dirty checking, and signals the JDBC driver to optimize the connection.

### 6. @Modifying @Query without context invalidation

WRONG
```java
@Modifying
@Query("update Post p set p.status = :s where p.publishedOn < :d")
int archiveOlderThan(@Param("s") Status s, @Param("d") LocalDate d);
// Persistence context still holds stale Post.status values
```

CORRECT
```java
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("update Post p set p.status = :s where p.publishedOn < :d")
int archiveOlderThan(@Param("s") Status s, @Param("d") LocalDate d);
```
Source: [`spring-data-query-methods.md`]. Without `clearAutomatically` the first-level cache returns pre-update entity state for the rest of the transaction.

### 7. Returning Page<Entity> to the controller

WRONG
```java
@GetMapping("/posts")
public Page<Post> list(Pageable pageable) {
    return postRepository.findAll(pageable);   // managed entities + LazyInit
}
```

CORRECT
```java
public interface PostSummary {
    Long getId();
    String getTitle();
    Instant getCreatedOn();
}

@Query("""
    select p.id as id, p.title as title, p.createdOn as createdOn
    from Post p
    """)
Page<PostSummary> findSummaries(Pageable pageable);

@GetMapping("/posts")
public Page<PostSummary> list(Pageable pageable) {
    return postRepository.findSummaries(pageable);
}
```
Source: [`spring-data-query-methods.md`, `records-spring-data-jpa.md`].

### 8. Derived method name with many parameters

WRONG
```java
List<Order> findByCustomerAndStatusAndCreatedOnBetweenAndTotalGreaterThanOrderByCreatedOnDesc(
    Customer c, Status s, Instant from, Instant to, BigDecimal min);
```

CORRECT — use Specification for dynamic filters
```java
public interface OrderRepository
    extends BaseJpaRepository<Order, Long>,
            JpaSpecificationExecutor<Order> {

    interface Specs {
        static Specification<Order> byCustomer(Customer c) {
            return (r, q, b) -> b.equal(r.get(Order_.customer), c);
        }
        static Specification<Order> byStatus(Status s) {
            return (r, q, b) -> b.equal(r.get(Order_.status), s);
        }
        static Specification<Order> createdBetween(Instant from, Instant to) {
            return (r, q, b) -> b.between(r.get(Order_.createdOn), from, to);
        }
    }
}

Specification<Order> spec = Specs.byCustomer(c)
    .and(Specs.byStatus(s))
    .and(Specs.createdBetween(from, to));
orderRepository.findAll(spec, Sort.by(Order_.CREATED_ON).descending());
```
Source: [`spring-data-jpa-specification.md`].

### 9. Pageable + JOIN FETCH on a @OneToMany

WRONG
```java
@Query("""
    select p from Post p
    left join fetch p.comments
    """)
Page<Post> findAllWithComments(Pageable pageable);
// HHH000104: firstResult/maxResults specified with collection fetch;
// applying in memory — the full table is loaded into the JVM!
```

CORRECT — two queries
```java
@Query("select p.id from Post p order by p.createdOn desc")
Page<Long> findPostIds(Pageable pageable);

@Query("""
    select distinct p from Post p
    left join fetch p.comments
    where p.id in :ids
    order by p.createdOn desc
    """)
List<Post> findWithComments(@Param("ids") List<Long> ids);
```
Source: [`spring-data-jpa-multiplebagfetchexception.md`].

### 10. findById().isPresent() for existence checks

WRONG
```java
if (postRepository.findById(id).isPresent()) { ... }
// fetches every column + initializes entity proxies
```

CORRECT
```java
if (postRepository.existsById(id)) { ... }

// Or for a custom predicate:
@Query("select case when count(p) > 0 then true else false end "
     + "from Post p where p.slug = :slug")
boolean existsBySlug(@Param("slug") String slug);
```
Source: [`spring-data-exists-query.md`, `spring-data-jpa-findbyid.md`].

## Performance Pitfalls

- `spring.jpa.open-in-view=true` (Spring Boot default) keeps the persistence context bound to the HTTP request thread, so every lazy access during JSON serialization fires a fresh SELECT and holds the JDBC connection through view rendering [`spring-boot-application-properties.md`].
- Calling `JpaRepository.save(detachedEntityWithAssignedId)` triggers a `SELECT` then a `MERGE` even when the row is new; in batch imports this generates N useless SELECTs. Prefer `persist` on a `HibernateRepository`/`BaseJpaRepository` [`best-spring-data-jparepository.md`].
- Forgetting `@Transactional(readOnly = true)` on query services keeps Hibernate's automatic dirty-check + flush enabled and forces the JDBC driver into read-write mode; on PostgreSQL this disables some optimizer hot paths [`spring-transactional-annotation.md`].
- Offset pagination (`PageRequest.of(page, size)`) degrades linearly with page index because the database must scan and discard `page * size` rows; switch to keyset for any deep pagination or batch traversal [`keyset-pagination-spring.md`, `spring-data-windowiterator.md`].
- A `Pageable` plus a `join fetch` on a `@OneToMany` triggers HHH000104, causing Hibernate to load the entire result set and paginate in JVM memory; split into an ID-page query and a fetch query [`spring-data-jpa-multiplebagfetchexception.md`].
- Returning entities from controllers re-opens lazy proxies during Jackson serialization (with OIV on) or throws `LazyInitializationException` (with OIV off); always project to DTO/record at the repository boundary [`records-spring-data-jpa.md`].
- Per-row `save()` inside a loop with no `hibernate.jdbc.batch_size` setting issues one network round-trip per INSERT; combine `persistAll` with `batch_size=30`, `order_inserts=true`, `order_updates=true` [`best-spring-data-jparepository.md`, `spring-boot-application-properties.md`].
- `@Modifying` without `clearAutomatically=true` leaves the first-level cache out of sync with the database; subsequent reads in the same transaction return pre-update state and overwrite the change on flush [`spring-data-query-methods.md`].
- `findBy...().isPresent()` hydrates every column and every secondary fetch defined as eager; `existsBy...` issues a `SELECT 1` with `LIMIT 1` [`spring-data-exists-query.md`].
- Streaming a query result without `try-with-resources` leaks the underlying JDBC `ResultSet` and connection; always close the `Stream` and keep it inside the transactional method [`spring-data-jpa-stream.md`].
- Unindexed `Sort` columns in `Pageable` cause a `filesort` (MySQL) or external sort (PostgreSQL) on every page request; index the exact column tuple and direction used by the sort [`keyset-pagination-spring.md`].

## Citations

- [`best-spring-data-jparepository.md`] — Why `JpaRepository.save` is wrong for JPA and how `HibernateRepository` exposes `persist`/`merge`/`update` with batching.
- [`custom-spring-data-repository.md`] — Pattern for adding a custom fragment interface and impl class to a Spring Data repository.
- [`jakarta-data-spring-hibernate.md`] — Jakarta Data integration with Spring Boot and Hibernate as a `JpaRepository` alternative.
- [`keyset-pagination-spring.md`] — Keyset (seek) pagination with Spring Data JPA and why it beats offset for deep paging.
- [`log-sql-spring-boot.md`] — Correct properties to log SQL with bind parameters in development (avoid `show_sql`).
- [`records-spring-data-jpa.md`] — Java records as DTO projections in Spring Data JPA `@Query` constructor expressions.
- [`spring-boot-application-properties.md`] — Recommended `application.properties` for JPA: `open-in-view=false`, batch size, ordering.
- [`spring-boot-performance-tuning.md`] — Spring Boot performance tuning checklist covering connection pool, JPA, cache.
- [`spring-data-base-repository.md`] — `BaseJpaRepository` from Hypersistence Utils, the recommended replacement for `JpaRepository`.
- [`spring-data-exists-query.md`] — Use `existsBy` / `SELECT 1` queries instead of `findBy().isPresent()`.
- [`spring-data-findall-anti-pattern.md`] — Why inheriting `findAll` in every repository is an anti-pattern and how to ban it.
- [`spring-data-jpa-findbyid.md`] — `findById` vs `getReferenceById` and when each is appropriate.
- [`spring-data-jpa-multiplebagfetchexception.md`] — Fix for `MultipleBagFetchException` and the Pageable + collection fetch warning HHH000104.
- [`spring-data-jpa-specification.md`] — Composable `Specification` definitions with the JPA Metamodel for dynamic filtering.
- [`spring-data-jpa-stream.md`] — Streaming repository methods with `try-with-resources` inside transactions.
- [`spring-data-query-by-example.md`] — Query-by-Example with `Example.of(probe)` for prototype-style filters.
- [`spring-data-query-methods.md`] — Derived query methods vs `@Query` JPQL/native vs `@Modifying` with `clearAutomatically`.
- [`spring-data-windowiterator.md`] — `WindowIterator` from Hypersistence Utils for batch traversal using keyset pagination.
- [`spring-hibernate-entity-listeners.md`] — Spring-managed `@EntityListeners` for auditing and event hooks.
- [`spring-petclinic-hypersistence-optimizer.md`] — Running Hypersistence Optimizer on the Spring PetClinic to detect anti-patterns.
- [`spring-request-level-memoization.md`] — Caching expensive lookups per HTTP request inside a Spring bean.
- [`spring-transactional-annotation.md`] — Best practice for `@Transactional`: service layer only, class-level `readOnly=true`, method-level overrides.
