# JPA/Hibernate Fetching Strategies and the N+1 Problem

## Core Principles

1. **Default to FetchType.LAZY and join-fetch only what you query.** JPA defaults ManyToOne and OneToOne to EAGER; override to LAZY. Load associations explicitly in query layer via JOIN FETCH, @EntityGraph, or DTO projections. Eager loading at mapping level causes uncontrolled query explosion. (hibernate-facts-the-importance-of-fetch-strategy.md, eager-fetching-is-a-code-smell.md)

2. **Use JOIN FETCH to prevent N+1 queries, but understand Cartesian product expansion.** JOIN FETCH loads associations in a single query but multiplies result rows by collection size. For a Post with 5 Comments, one JOIN FETCH query returns 5 rows (same Post repeated). Detect via row count mismatch: use DISTINCT in JPQL or set distinct(true) in Criteria. (hibernate-facts-multi-level-fetching.md)

3. **Cartesian product explosion occurs with multiple collection fetches; use DTO projections instead.** Joining two @OneToMany collections (e.g., Post → Comments and Post → Tags) produces rows = comments_count × tags_count. For Post(5 Comments, 3 Tags), one query returns 15 rows. Solution: DTO projection with separate queries or jOOQ MULTISET. (hibernate-facts-multi-level-fetching.md, fetch-multiple-to-many-jooq-multiset.md)

4. **DTO projections (constructor-based or interface-based) avoid identity map pollution and Cartesian products.** Entity fetching merges into persistence context; projection queries return detached, lightweight objects. Use new com.example.PostDTO(p.id, p.title, c.text) in JPQL SELECT or Spring Data interface-based projection. Projections bypass lazy initialization entirely. (dto-projection-jpa-query.md, spring-jpa-dto-projection.md, one-to-many-dto-projection-hibernate.md)

5. **Never use FetchType.EAGER at entity mapping level; always override with query-level fetch plans.** EAGER couples fetch strategy to entity definition, forcing all queries to load associations regardless of need. Use @EntityGraph or JOIN FETCH in queries instead. If legacy code has EAGER, override with @EntityGraph(attributePaths = {...}) or FetchMode.SELECT. (eager-fetching-is-a-code-smell.md, fetchtype-eager-fetchgraph.md)

6. **Open Session In View (OSIV) hides N+1 problems by keeping session open until HTTP response writes; disable it in production.** OSIV allows lazy loading in view layer (JSP, template) without explicit JOIN FETCH. This masks N+1 queries that occur during serialization. Tests without OSIV catch N+1; production deploys with OSIV appear fast but exhaust connection pools. Disable spring.jpa.open-in-view=false. (the-open-session-in-view-anti-pattern.md)

7. **LazyInitializationException ("could not initialize proxy") occurs when accessing lazy proxies outside session/transaction.** Lazy-loaded associations are Javassist proxies; accessing them outside session throws exception. Solutions: (a) join-fetch before transaction closes, (b) call Hibernate.initialize() inside transaction, (c) switch to DTO projection, (d) use @Transactional on repository/service method. (initialize-lazy-proxies-collections-jpa-hibernate.md, how-to-lazy-load-entity-properties-with-hibernate.md)

8. **Pagination with collections requires two queries: one for IDs, one for full entities with associations.** Set firstresult()/setmaxresults() on a query with JOIN FETCH collection applies limit to result rows, not entities. Fetch 10 Posts with 5 Comments each returns 50 rows. Correct pattern: query for Post IDs with pagination, then JOIN FETCH Comments by ID. Spring Data @Query with Pageable + separate association fetch. (join-fetch-pagination-spring.md, fix-hibernate-hhh000104-entity-fetch-pagination-warning-message.md)

9. **@LazyCollection(EXTRA) generates N+1 queries instead of loading entire collection; use @BatchSize or explicit JOIN FETCH.** EXTRA mode loads only when size() or contains() called, issuing separate count/select per entity. @BatchSize(size=10) loads associations in groups of 10. For predictable access patterns, explicit JOIN FETCH in query is faster. (hibernate-extra-lazy-collections.md)

10. **Prefer Set<T> over List<T> for @OneToMany without @OrderBy; bags allow duplicates in Cartesian products.** List (bag) semantics allow duplicate entries in JOIN FETCH results without DISTINCT. Set semantics deduplicate. For unordered collections, Set is semantically correct and performs better. Use @OrderBy if order is required. (hibernate-facts-favoring-sets-vs-bags.md)

## Decision Trees

### Should I use JOIN FETCH for this association?

```
Is this association always needed in this query use case?
├─ YES (e.g., always display Post with author name)
│  └─ Are you fetching multiple @OneToMany collections on same root?
│     ├─ YES → Use DTO projection or jOOQ MULTISET (avoid Cartesian product)
│     └─ NO  → Use JOIN FETCH (DISTINCT if bag semantics) + @BatchSize fallback
├─ NO (e.g., Comments only needed on detail view, not list)
│  └─ Is this a large collection (>100 rows per entity)?
│     ├─ YES → Use explicit repository query in service layer; LAZY at mapping
│     └─ NO  → FetchType.LAZY + Hibernate.initialize() where needed
└─ DEPENDS ON CONTEXT (e.g., admin vs user view)
   └─ Use @EntityGraph / FetchMode override in query; never set at mapping
```

### How do I fix LazyInitializationException?

```
LazyInitializationException triggered during serialization or view rendering

├─ Is association accessed after transaction closed (e.g., in controller/view)?
│  ├─ YES → Solution A: Add @Transactional to repository method
│  ├─ YES → Solution B: Use Hibernate.initialize(entity.getCollection()) before tx close
│  ├─ YES → Solution C: Refactor to DTO projection (eliminates lazy proxies)
│  └─ YES → Solution D: If Spring MVC, enable OSIV (anti-pattern; use for legacy only)
└─ Is association accessed during @Transactional method but not eagerly fetched?
   ├─ YES → Add JOIN FETCH to repository @Query
   └─ YES → Or @EntityGraph(attributePaths = "association")
```

### FetchType.EAGER vs LAZY: Decision Matrix

| Scenario | Recommendation | Reason |
|----------|---|---|
| ManyToOne/OneToOne (e.g., Post.author) | LAZY by default, JOIN FETCH in queries | EAGER forces load on every Post query |
| OneToMany collection, always displayed together (e.g., Post.comments on detail page) | LAZY at mapping, JOIN FETCH in detail query | Prevents explosion on list queries |
| OneToMany, large collection (>1000 rows) | LAZY, use DTO or pagination | Cartesian product with JOIN FETCH unmanageable |
| OneToMany, small collection (<50 rows), always shown (e.g., Post.tags) | LAZY at mapping, JOIN FETCH in base query, @BatchSize(10) fallback | JOIN FETCH safe for small collections |
| Nested collections (Post.comments, Comment.likes) | LAZY everywhere, single-level JOIN FETCH only | Multi-level JOIN FETCH causes Cartesian explosion |
| Legacy EAGER mapping, cannot change code | Use @EntityGraph(attributePaths={}) to override per query | Do not propagate EAGER; quarantine it |

## Anti-Patterns and Corrections

### WRONG: FetchType.EAGER at entity mapping forces unwanted eager loads

```java
// WRONG: Couples fetch strategy to entity; forces EAGER on ALL queries
@Entity
public class Post {
    @ManyToOne(fetch = FetchType.EAGER) // BAD
    private Author author;
    
    @OneToMany(mappedBy = "post", fetch = FetchType.EAGER) // BAD
    private List<Comment> comments;
}

// Even a simple count query triggers load of all comments
Long count = em.createQuery("SELECT COUNT(p) FROM Post p", Long.class).getSingleResult();
// → SELECT COUNT(*) FROM posts (good)
// → SELECT author.*, comment.* ... (unwanted!)
```

**CORRECT: Keep associations LAZY; override per query**

```java
// CORRECT: Map associations as LAZY (JPA default for collections is LAZY; set ManyToOne/OneToOne explicitly)
@Entity
public class Post {
    @ManyToOne(fetch = FetchType.LAZY)
    private Author author;
    
    @OneToMany(mappedBy = "post", fetch = FetchType.LAZY) // Default; explicit for clarity
    private Set<Comment> comments; // Set not List (no bag duplicates)
}

// In repository layer, fetch only what is needed
@Query("SELECT p FROM Post p JOIN FETCH p.author WHERE p.id = :id")
Post getPostWithAuthor(@Param("id") Long id);

@Query("SELECT p FROM Post p JOIN FETCH p.comments WHERE p.id = :id")
Post getPostWithComments(@Param("id") Long id);
```
(hibernate-facts-the-importance-of-fetch-strategy.md, eager-fetching-is-a-code-smell.md)

---

### WRONG: Multiple @OneToMany JOINs produce Cartesian product

```java
// WRONG: Joining two collections causes row explosion
// Post has 5 comments, 3 tags
@Query("SELECT p FROM Post p " +
       "JOIN FETCH p.comments c " +
       "JOIN FETCH p.tags t " +
       "WHERE p.id = :id")
Post getPost(@Param("id") Long id);
// → Returns 15 rows (5 × 3); same Post object repeated 15 times
// → Causes N+1 on second query if pagination applied
```

**CORRECT: Use DTO projection or single-level JOIN FETCH**

```java
// CORRECT A: DTO projection avoids identity merge, prevents Cartesian product
@Query("SELECT new com.example.PostWithAssociationsDTO(" +
       "p.id, p.title, p.author, c, t) " +
       "FROM Post p " +
       "LEFT JOIN p.comments c " +
       "LEFT JOIN p.tags t " +
       "WHERE p.id = :id")
PostWithAssociationsDTO getPost(@Param("id") Long id);
// → Single query, returns data as-is (no deduplication needed)

// CORRECT B: Separate queries (jOOQ MULTISET or manual aggregation)
@Query("SELECT p FROM Post p WHERE p.id = :id")
Post getPost(@Param("id") Long id); // LAZY comments, tags

// Then in service:
List<Comment> comments = commentRepository.findByPostId(id);
List<Tag> tags = tagRepository.findByPostId(id);
post.setComments(comments); post.setTags(tags);
// → Two efficient queries, no Cartesian product
```
(hibernate-facts-multi-level-fetching.md, fetch-multiple-to-many-jooq-multiset.md)

---

### WRONG: Ignoring default JPA fetch plan vs. query fetch plan

```java
// WRONG: Assumes @Entity mapping overrides query layer
@Entity
public class Post {
    @ManyToOne(fetch = FetchType.LAZY)
    private Author author;
}

// Repository:
@Query("SELECT p FROM Post p WHERE p.id = :id")
Post getPost(@Param("id") Long id);
// → Author is LAZY; calling post.getAuthor().getName() throws LazyInitializationException
// Developer expected LAZY to mean "load me if accessed"; actually means "do not load"
```

**CORRECT: Query layer controls fetch plan**

```java
// Default JPA fetch plan (mapping-level) is a fallback; query always overrides
@Entity
public class Post {
    @ManyToOne(fetch = FetchType.LAZY) // Mapping default (used if query does not override)
    private Author author;
}

// In repository:
@Query("SELECT p FROM Post p JOIN FETCH p.author WHERE p.id = :id")
Post getPostWithAuthor(@Param("id") Long id);
// → Author is loaded in same query; accessing getName() is safe

@Query("SELECT p FROM Post p WHERE p.id = :id")
Post getPostAlone(@Param("id") Long id);
// → Author is LAZY; load explicitly if needed
```
(jpa-default-fetch-plan.md)

---

### WRONG: Pagination with JOIN FETCH collection misapplies limit

```java
// WRONG: setFirstResult/setMaxResults applies AFTER join
List<Post> posts = em.createQuery(
    "SELECT p FROM Post p JOIN FETCH p.comments WHERE p.author.id = :aid", Post.class)
    .setParameter("aid", 1L)
    .setFirstResult(0)
    .setMaxResults(10) // Limits RESULT ROWS, not Posts!
    .getResultList();
// If author has 20 posts × 5 comments = 100 rows
// → Fetches first 10 rows = 2 posts + 5 comments + fragmented post = broken pagination
```

**CORRECT: Two-query pattern for pagination with collections**

```java
// CORRECT: Fetch Post IDs first with pagination, then load associations
@Query("SELECT DISTINCT p.id FROM Post p WHERE p.author.id = :aid ORDER BY p.id")
Page<Long> getPostIds(@Param("aid") Long aid, Pageable pageable);

@Query("SELECT DISTINCT p FROM Post p " +
       "LEFT JOIN FETCH p.comments c " +
       "WHERE p.id IN :ids ORDER BY p.id, c.id")
List<Post> getPostsWithComments(@Param("ids") List<Long> ids);

// In service:
Page<Long> postIds = postRepository.getPostIds(authorId, PageRequest.of(0, 10));
List<Post> posts = postRepository.getPostsWithComments(postIds.getContent());
// → First query: 1 SELECT COUNT, 1 SELECT Post.id (10 rows, paginated)
// → Second query: 1 SELECT Post, Comment for those 10 posts (up to 10 × N rows, but correct entities)
```
(join-fetch-pagination-spring.md, fix-hibernate-hhh000104-entity-fetch-pagination-warning-message.md)

---

### WRONG: @LazyCollection(EXTRA) generates N+1 on collection access

```java
// WRONG: EXTRA mode loads collection on-demand with separate query
@Entity
public class Post {
    @OneToMany(mappedBy = "post")
    @LazyCollection(LazyCollectionOption.EXTRA) // BAD
    private List<Comment> comments;
}

// In service:
List<Post> posts = postRepository.findAll(); // 1 query
for (Post p : posts) {
    int size = p.getComments().size(); // N queries! (one count per post)
    // EXTRA mode issues: SELECT COUNT(*) FROM comments WHERE post_id = ?
}
// → Total: 1 + N queries (N+1 problem)
```

**CORRECT: Use @BatchSize or explicit JOIN FETCH**

```java
// CORRECT A: @BatchSize loads associations in groups
@Entity
public class Post {
    @OneToMany(mappedBy = "post")
    @BatchSize(size = 10)
    private List<Comment> comments;
}

// In service:
List<Post> posts = postRepository.findAll(); // 1 query: SELECT * FROM posts
for (Post p : posts) {
    int size = p.getComments().size(); // Triggers batch load
    // Hibernate: SELECT c.* FROM comments WHERE post_id IN (?, ?, ..., ?) // (10 items)
}
// → Total: 1 + CEIL(N/10) queries (much better than N+1)

// CORRECT B: Explicit JOIN FETCH for predictable access
@Query("SELECT DISTINCT p FROM Post p LEFT JOIN FETCH p.comments")
List<Post> getAllPostsWithComments();
// → 1 query, no batching needed
```
(hibernate-extra-lazy-collections.md)

---

### WRONG: Open Session In View enabled; N+1 queries masked

```java
// WRONG: OSIV allows lazy loading in view layer, hiding N+1
// spring.jpa.open-in-view=true (Hibernate Spring Boot default)

@RestController
public class PostController {
    @GetMapping("/posts")
    public List<PostDTO> getPosts() {
        List<Post> posts = postRepository.findAll(); // 1 query
        // Session still open; lazy loading allowed in @RestControllerAdvice or response serialization
        return posts.stream().map(p -> new PostDTO(
            p.getId(),
            p.getTitle(),
            p.getAuthor().getName() // OSIV: lazy load happens here (N+1!)
        )).collect(toList());
    }
}
// → N+1 queries hidden by OSIV; test without OSIV to catch this
```

**CORRECT: Disable OSIV; use explicit fetch or DTO projection**

```java
// CORRECT: Disable OSIV and use explicit fetch strategy
# application.properties
spring.jpa.open-in-view=false

// In repository:
@Query("SELECT p FROM Post p JOIN FETCH p.author")
List<Post> findAllWithAuthor();

// In controller:
@GetMapping("/posts")
public List<PostDTO> getPosts() {
    List<Post> posts = postRepository.findAllWithAuthor(); // Explicit join
    return posts.stream().map(p -> new PostDTO(
        p.getId(),
        p.getTitle(),
        p.getAuthor().getName() // Safe; author already loaded
    )).collect(toList());
}
// → 1 query, no lazy loading surprise in view layer
```
(the-open-session-in-view-anti-pattern.md)

---

### WRONG: @Basic(fetch=LAZY) attribute without bytecode enhancement causes extra query

```java
// WRONG: Lazy loading of @Basic attributes requires bytecode enhancement (not always enabled)
@Entity
public class Post {
    private Long id;
    private String title;
    
    @Basic(fetch = FetchType.LAZY)
    @Column(columnDefinition = "TEXT")
    private String content; // Large column, lazy load to speed up queries
}

// Without bytecode enhancement enabled:
Post p = em.find(Post.class, 1L);
String content = p.getContent(); // Silently loads EAGER despite @Basic(LAZY)
// → @Basic(LAZY) ignored; no separate query
```

**CORRECT: Enable bytecode enhancement or use entity projection**

```java
// CORRECT A: Enable Hibernate bytecode enhancement in build
// Maven: org.hibernate:hibernate-enhance-maven-plugin
// Gradle: org.hibernate:hibernate-gradle-plugin
// build.gradle:
plugins {
    id 'org.hibernate.orm'
}
hibernateEnhance {
    enableLazyInitialization = true
}

// Then @Basic(LAZY) works:
@Entity
public class Post {
    @Basic(fetch = FetchType.LAZY)
    private String content;
}
Post p = em.find(Post.class, 1L); // SELECT id, title (not content)
String content = p.getContent(); // SELECT content WHERE id = 1

// CORRECT B: Use entity projection if enhancement not available
@Query("SELECT new com.example.PostPreviewDTO(p.id, p.title) FROM Post p WHERE p.id = :id")
PostPreviewDTO getPostPreview(@Param("id") Long id);
// → No bytecode magic needed; projection skips content column
```
(how-to-lazy-load-entity-properties-with-hibernate.md, the-best-way-to-lazy-load-entity-attributes-using-jpa-and-hibernate.md)

## Performance Pitfalls

1. **HHH000104 warning: "firstresult/maxresults specified with collection fetch; applying in memory."** Pagination with JOIN FETCH collection loads ALL rows into memory, then slices in-memory. For 10,000 posts with 5 comments, loads 50,000 rows to memory to apply limit. Solution: Use two-query pattern (fetch IDs paginated, then load associations by ID). (fix-hibernate-hhh000104-entity-fetch-pagination-warning-message.md)

2. **Cartesian product explosion: Multiple collection joins multiply result rows unpredictably.** Post with 5 Comments, 3 Tags, 2 Authors (if many-to-many) → 5×3×2 = 30 rows from single Post. Each JOIN FETCH multiplies rows by collection size. Accumulates with nested collections. Diagnosis: Query returns more rows than entities; row_count > entity_count. Solution: DTO projection, jOOQ MULTISET, or one-level JOIN FETCH only. (hibernate-facts-multi-level-fetching.md)

3. **LazyInitializationException after transaction closes: lazy proxies accessed in view layer, outside session.** Lazy-loaded @OneToMany, @ManyToOne accessed after em.flush()/transaction commit. Javassist proxy tries to initialize, session closed, exception thrown. Occurs in REST controllers, JSP renderers, Jackson serialization. Solution: @Transactional on repository method, JOIN FETCH before commit, DTO projection, or Hibernate.initialize() within session. (initialize-lazy-proxies-collections-jpa-hibernate.md)

4. **EAGER fetch plan query explosion: Each @ManyToOne(fetch=EAGER) added to entity multiplies SELECT count.** Post with 3 EAGER ManyToOne associations (author, category, series) → 1 SELECT Post, 3 LEFT OUTER JOINs (or 3 extra queries if not joined). Scale to 10 EAGER associations → unmanageable query cost. Hidden cost: affects ALL queries on entity, even aggregate queries needing only ID. (eager-fetching-is-a-code-smell.md)

5. **Undetected N+1 hidden by OSIV: LazyInitializationException does not occur; queries fire during serialization.** OSIV keeps session open through view rendering, allowing lazy loads to succeed silently. Tests run with OSIV enabled see 1 slow query; production sees N+1. Profiling misses N+1 because it is spread across HTTP response phase. Solution: Disable OSIV in test profile, run assertions with SQLStatementCountValidator. (the-open-session-in-view-anti-pattern.md, how-to-detect-the-n-plus-one-query-problem-during-testing.md)

6. **MultipleBagFetchException: Fetching more than one List (bag) without @OrderBy causes ambiguous row deduplication.** Joining two @OneToMany(fetch=LAZY) List fields in same query. Hibernate cannot deduplicate rows (Sets use equals/hashCode; Lists do not). Error: "Cannot have two rows with collection fetch of a bag." Solution: Change one to Set, add @OrderBy to both, or use DTO projection. (hibernate-facts-multi-level-fetching.md)

7. **@LazyCollection(EXTRA) N+1 on collection size/iteration: Small benefit for count() or contains(), high cost for full iteration.** EXTRA mode optimizes size() to issue SELECT COUNT(*), avoids loading full collection. But accessing all items triggers separate SELECT for each collection iteration. If loop iterates all comments (foreach p.getComments()), EXTRA issues N queries for full load after initial loads. Undetectable in unit tests. Solution: Use @BatchSize(size=10) or explicit JOIN FETCH based on access pattern. (hibernate-extra-lazy-collections.md)

## Citations

1. eager-fetching-is-a-code-smell.md — FetchType.EAGER is a mapping-level code smell; query-based strategies preferred.
2. hibernate-facts-the-importance-of-fetch-strategy.md — Four fetch strategies, JPA defaults, impact on query generation.
3. hibernate-facts-multi-level-fetching.md — Multi-level collections, Cartesian products, join ordering, bag vs. set semantics.
4. dto-projection-jpa-query.md — Constructor-based DTO projections, compact SELECT, avoiding entity identity merge.
5. fetchtype-eager-fetchgraph.md — Override EAGER mapping with @EntityGraph, FetchMode.SELECT/JOIN per query.
6. the-open-session-in-view-anti-pattern.md — OSIV hides N+1 during view rendering, connection pool exhaustion, disabling OSIV.
7. hibernate-extra-lazy-collections.md — @LazyCollection(EXTRA) semantics, N+1 on collection access, vs. @BatchSize.
8. hibernate-facts-favoring-sets-vs-bags.md — Set semantics for @OneToMany without @OrderBy, duplicate deduplication in JOIN FETCH.
9. how-to-detect-the-n-plus-one-query-problem-during-testing.md — SQLStatementCountValidator, datasource-proxy, assertion-based N+1 detection.
10. fix-hibernate-hhh000104-entity-fetch-pagination-warning-message.md — HHH000104 warning, in-memory slicing, two-query pagination pattern.
11. initialize-lazy-proxies-collections-jpa-hibernate.md — Hibernate.initialize(), JOIN FETCH before transaction close, LazyInitializationException recovery.
12. jpa-default-fetch-plan.md — Default fetch plan (mapping-level) vs. query fetch plan override, Criteria expectations.
13. join-fetch-pagination-spring.md — Pagination with collections, HHH000104, two-query solution with Page<ID> + separate load.
14. how-to-lazy-load-entity-properties-with-hibernate.md — @Basic(fetch=LAZY), bytecode enhancement requirement, enableLazyInitialization config.
15. one-to-many-dto-projection-hibernate.md — DTO aggregation, ResultTransformer, avoiding Cartesian products in result set.
16. spring-jpa-dto-projection.md — Interface-based dynamic proxies, class-based constructor projections, Spring Data projection repositories.
17. the-best-way-to-lazy-load-entity-attributes-using-jpa-and-hibernate.md — @Basic(fetch=LAZY) with bytecode enhancement, enableLazyInitialization=true.
18. emulate-left-join-fetch-using-projections.md — Record-based projections, optional intermediates, LEFT JOIN in projection queries.
19. fetch-multiple-to-many-jooq-multiset.md — jOOQ MULTISET, avoiding Cartesian products for multiple collections, native SQL alternative.
20. hibernate-query-fail-on-pagination-over-collection-fetch.md — Pagination over collection fetch detection, strict configuration.
21. initialize-lazy-proxies-collections-jpa-hibernate.md — Proxy initialization patterns, when to use Hibernate.initialize() vs. JOIN FETCH.
