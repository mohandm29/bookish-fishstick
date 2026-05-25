# JPA / Hibernate Code Review — Fix Recipes

Copy-pasteable corrected snippets keyed by rule ID. Use as the **Fix** block in review findings. Adapt names to the user's domain types.

---

## R01 — `@ManyToMany` cascade misuse

```java
// WRONG
@ManyToMany(cascade = CascadeType.ALL)
private Set<Tag> tags = new HashSet<>();

// CORRECT — only PERSIST + MERGE on @ManyToMany
@ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
@JoinTable(
    name = "post_tag",
    joinColumns = @JoinColumn(name = "post_id"),
    inverseJoinColumns = @JoinColumn(name = "tag_id")
)
private Set<Tag> tags = new HashSet<>();
```

## R02 — Unidirectional `@OneToMany` with cascade

```java
// WRONG — creates extra join table, broken delete semantics
@OneToMany(cascade = CascadeType.ALL)
private List<Comment> comments = new ArrayList<>();

// CORRECT — bidirectional, parent owns via mappedBy
@OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
private List<Comment> comments = new ArrayList<>();

// On Comment:
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "post_id")
private Post post;

// Or — if unidirectional is required, force a real FK (no join table):
@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
@JoinColumn(name = "post_id")  // skips the join table
private List<Comment> comments = new ArrayList<>();
```

## R03 — Bidirectional sync helpers

```java
// On the parent:
public void addComment(Comment c) {
    comments.add(c);
    c.setPost(this);
}

public void removeComment(Comment c) {
    comments.remove(c);
    c.setPost(null);
}
```

## R04 — `List` → `Set` for `@ManyToMany`

```java
// WRONG
@ManyToMany
private List<Tag> tags;

// CORRECT
@ManyToMany
private Set<Tag> tags = new HashSet<>();
```

## R05 — `equals` / `hashCode` with a business key

```java
// CORRECT — equality via business key (UUID or natural key)
@Entity
public class Book {
    @Id @GeneratedValue
    private Long id;

    @NaturalId
    @Column(nullable = false, unique = true, updatable = false)
    private String isbn;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Book that)) return false;
        return Objects.equals(isbn, that.isbn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isbn);
    }
}

// If no business key, use a UUID assigned at construction:
@Id
private final UUID id = UUID.randomUUID();
```

## R06 — Force LAZY on collections

```java
// WRONG
@OneToMany(fetch = FetchType.EAGER)
private Set<Comment> comments;

// CORRECT — load explicitly via JOIN FETCH / @EntityGraph when needed
@OneToMany(mappedBy = "post", fetch = FetchType.LAZY)
private Set<Comment> comments = new HashSet<>();
```

## R07 — Explicit LAZY on `@ManyToOne` / `@OneToOne`

```java
// WRONG (implicit EAGER)
@ManyToOne
private Author author;

// CORRECT
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "author_id")
private Author author;

// For @OneToOne non-owning side, use @MapsId to make it effectively lazy:
@OneToOne(fetch = FetchType.LAZY)
@MapsId
@JoinColumn(name = "id")
private User user;
```

## R08 — Replace `@LazyCollection(EXTRA)`

```java
// WRONG
@OneToMany(mappedBy = "post")
@LazyCollection(LazyCollectionOption.EXTRA)
private List<Comment> comments;

// CORRECT — derive size from a count query
public long countCommentsForPost(Long postId) {
    return em.createQuery(
        "select count(c) from Comment c where c.post.id = :id", Long.class)
        .setParameter("id", postId)
        .getSingleResult();
}
```

## R09 — Fix `MultipleBagFetchException` / multi-collection JOIN FETCH

```java
// WRONG
String q = """
    select p from Post p
      join fetch p.comments
      join fetch p.tags
    where p.id = :id
    """;

// CORRECT — two queries, then merge
Post post = em.createQuery(
    "select p from Post p join fetch p.comments where p.id = :id", Post.class)
    .setParameter("id", id).getSingleResult();

em.createQuery(
    "select p from Post p join fetch p.tags where p.id = :id", Post.class)
    .setParameter("id", id).getSingleResult();
// Hibernate populates the cached `post` instance's tags too
```

## R10 — Pagination with collections (two-step)

```java
// WRONG — triggers HHH000104, in-memory pagination
@Query("select p from Post p left join fetch p.comments")
Page<Post> findAllWithComments(Pageable pageable);

// CORRECT — page IDs first, then fetch
@Query("select p.id from Post p")
Page<Long> findPostIds(Pageable pageable);

@Query("select distinct p from Post p left join fetch p.comments where p.id in :ids")
List<Post> findPostsWithComments(@Param("ids") List<Long> ids);

// In the service:
Page<Long> idPage = repo.findPostIds(pageable);
List<Post> posts  = repo.findPostsWithComments(idPage.getContent());
return new PageImpl<>(posts, pageable, idPage.getTotalElements());
```

## R11 — IDENTITY → SEQUENCE for batching

```java
// WRONG (esp. on Postgres / Oracle)
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;

// CORRECT
@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post_seq")
@SequenceGenerator(name = "post_seq", sequenceName = "post_seq", allocationSize = 50)
private Long id;

// In application.properties:
// hibernate.jdbc.batch_size=50
// hibernate.order_inserts=true
// hibernate.order_updates=true
```

## R12 — Explicit strategy

```java
// CORRECT for Postgres / Oracle
@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "entity_seq")
@SequenceGenerator(name = "entity_seq", sequenceName = "entity_seq", allocationSize = 50)
private Long id;
```

## R13 — Batch insert with flush / clear

```java
// WRONG — unbounded persistence context
for (Order order : orders) {
    em.persist(order);
}

// CORRECT
int batchSize = 50;
for (int i = 0; i < orders.size(); i++) {
    em.persist(orders.get(i));
    if (i % batchSize == 0 && i > 0) {
        em.flush();
        em.clear();
    }
}
em.flush();
em.clear();
```

## R14 — Bulk delete

```java
// WRONG
List<Comment> stale = repo.findByCreatedAtBefore(cutoff);
for (Comment c : stale) repo.delete(c);

// CORRECT
@Modifying
@Query("delete from Comment c where c.createdAt < :cutoff")
int deleteOlderThan(@Param("cutoff") Instant cutoff);
```

## R15 — Add `@Version`

```java
@Entity
public class Product {
    @Id @GeneratedValue
    private Long id;

    @Version
    private Long version;

    private String name;
    private BigDecimal price;
}
```

## R16 — Locking on read-then-write

```java
// CORRECT — pessimistic on a known hot row
Account acct = em.find(
    Account.class,
    accountId,
    LockModeType.PESSIMISTIC_WRITE
);
acct.debit(amount);
// commit releases the lock
```

## R17 — `readOnly = true`

```java
@Service
public class ProductService {

    @Transactional(readOnly = true)
    public List<ProductDto> search(String term) {
        return repo.searchProjections(term);
    }

    @Transactional
    public Product create(ProductDto dto) {
        return repo.save(dto.toEntity());
    }
}
```

## R18 — Self-invocation fix

```java
// WRONG — proxy bypass
@Service
public class OrderService {
    @Transactional public void outer() { this.inner(); }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inner() { /* new TX expected — does NOT happen */ }
}

// CORRECT — split into two beans
@Service
public class OrderService {
    private final OrderInnerService inner;

    @Transactional public void outer() { inner.inner(); }
}

@Service
public class OrderInnerService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void inner() { /* runs in new TX */ }
}
```

## R19 — Disable OSIV

```yaml
# application.yml
spring:
  jpa:
    open-in-view: false
```

```java
// Fetch what the view needs in the service layer
@Transactional(readOnly = true)
public PostView load(Long id) {
    Post post = repo.findById(id, EntityGraphs.named("Post.comments"));
    return PostView.from(post);  // detached DTO, no lazy access in controller
}
```

## R20 — Disable `enable_lazy_load_no_trans`

```properties
# application.properties — DELETE this line if present
hibernate.enable_lazy_load_no_trans=true
```

Fetch eagerly in the service layer instead (see R19).

---

## General fix patterns

- **Always pair a finding with a fix.** Never report a problem without a CORRECT block.
- **Adapt names**: replace `Post`, `Comment`, `Account`, `Order` with the user's actual domain types.
- **Keep fixes minimal**: change the smallest surface area that resolves the rule. Don't rewrite the file.
