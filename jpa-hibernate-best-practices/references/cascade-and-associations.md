# Entity Associations and Cascade Types

## Core Principles

- Cascade only from parent to child — cascading from child to parent is a mapping code smell and corrupts entity life cycles [a-beginners-guide-to-jpa-and-hibernate-cascade-types.md].
- Never default to `CascadeType.ALL` on `@ManyToMany` — it propagates `REMOVE` across the join table to unrelated parents, deleting entities the user did not ask to delete [a-beginners-guide-to-jpa-and-hibernate-cascade-types.md].
- Always set `FetchType.LAZY` explicitly on `@ManyToOne` and `@OneToOne` — JPA defaults them to `EAGER` which causes N+1 and over-fetching that cannot be overridden at query time [manytoone-jpa-hibernate.md].
- Map bidirectional `@OneToOne` with `@MapsId` on the child side — it shares the PK with the parent, eliminates the extra join-table-like schema, and avoids the secondary SELECT that `optional=false` alone cannot fix [the-best-way-to-map-a-onetoone-relationship-with-jpa-and-hibernate.md].
- Prefer a bidirectional `@OneToMany` (with `mappedBy` on the parent and `@ManyToOne` owning the FK) over a unidirectional `@OneToMany` — the owning `@ManyToOne` side generates the most efficient SQL [the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate.md].
- Always provide `addX`/`removeX` synchronization helpers on the parent side of bidirectional associations — only synchronized graphs are guaranteed to flush correctly across Hibernate versions [jpa-hibernate-synchronize-bidirectional-entity-associations.md].
- Use `List` (not `Set`) for bidirectional `@OneToMany` — fetched child rows are already distinct by PK, and a constant `hashCode` collapses `HashSet` into a single bucket, killing performance [set-bidirectional-onetomany.md].
- Use `Set` (not `List`) for `@ManyToMany` — with a `List`, Hibernate deletes every join-table row and re-inserts the survivors on each modification [the-best-way-to-use-the-manytomany-annotation-with-jpa-and-hibernate.md].
- Implement `equals`/`hashCode` so they remain stable across all entity state transitions — use a business key when available, else compare a non-null `id` and return a constant `getClass().hashCode()` [hibernate-facts-equals-and-hashcode.md].
- Use `orphanRemoval = true` (not just `CascadeType.REMOVE`) when child life-cycle is strictly bound to the parent collection — `CascadeType.REMOVE` only fires on parent delete, `orphanRemoval` fires on dissociation [orphanremoval-jpa-hibernate.md].
- Add `@JoinColumn` to any unidirectional `@OneToMany` you cannot convert to bidirectional — without it, Hibernate generates a redundant join table plus extra UPDATE statements [the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate.md].
- Map `@ManyToMany` with extra columns as two `@ManyToOne` associations to an `@Embeddable`-keyed link entity — pure `@ManyToMany` cannot carry payload columns [the-best-way-to-map-a-many-to-many-association-with-extra-columns-when-using-jpa-and-hibernate.md].

## Decision Trees

### Which side owns the relation?
```
Is the association to-one (@ManyToOne / unidirectional @OneToOne)?
  YES -> The to-one side owns the FK. Map @JoinColumn here.
  NO  -> Is it @OneToMany backed by a FK in the child table?
            YES -> The @ManyToOne child owns it. Parent uses mappedBy.
            NO  -> Is it @ManyToMany?
                     YES -> One side owns @JoinTable, the other uses mappedBy.
                     NO  -> @OneToOne bidirectional: child uses @MapsId,
                            parent uses mappedBy.
```
[the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate.md, the-best-way-to-map-a-onetoone-relationship-with-jpa-and-hibernate.md]

### Need cascade? ALL vs PERSIST/MERGE/REMOVE
```
Is child life-cycle strictly owned by the parent (composition)?
  YES -> Is it @OneToOne or @OneToMany?
            YES -> cascade = ALL, orphanRemoval = true.
            NO  -> (@ManyToMany) cascade = {PERSIST, MERGE} only.
                   NEVER ALL/REMOVE — it deletes the other parent.
  NO  -> (Association/aggregation) cascade = {} or {PERSIST, MERGE}.
         Manage child life-cycle explicitly via repository calls.
```
[a-beginners-guide-to-jpa-and-hibernate-cascade-types.md, orphanremoval-jpa-hibernate.md]

### @ElementCollection vs @OneToMany
```
Does the child have its own identity (PK, references from elsewhere)?
  YES -> Map as @OneToMany with @ManyToOne owning side.
  NO  -> Is the child a value object that rarely changes?
           YES -> @ElementCollection + @CollectionTable
                  (with @OrderColumn to enable id-bag optimization).
           NO  -> Promote to @OneToMany. ElementCollection rewrites
                  the whole collection on each change.
```
[how-to-optimize-unidirectional-collections-with-jpa-and-hibernate.md]

### Bidirectional or unidirectional?
```
Do you query the children from the parent in the same use case?
  YES -> Bidirectional with mappedBy on the parent (collection of children).
         Always provide addX/removeX sync helpers.
  NO  -> Stay unidirectional @ManyToOne on the child side.
         Fetch children with a JPQL/Criteria query when needed.
         (@ManyToOne is "OneToFew" — collections only fit small N.)
```
[the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate.md, jpa-bidirectional-sync-methods.md]

### Removing children: cascade vs orphanRemoval vs DB ON DELETE
```
Need to delete child when parent is deleted only?
  -> CascadeType.REMOVE (or ALL).
Need to delete child when dissociated from the parent's collection?
  -> orphanRemoval = true (implies REMOVE on dissociation).
Operating from outside JPA (bulk SQL, multi-entity cleanup)?
  -> Use bulk DELETE statements in a custom repository method;
     ON DELETE CASCADE in the schema covers the database side.
```
[orphanremoval-jpa-hibernate.md, cascade-delete-unidirectional-associations-spring.md]

## Anti-patterns: WRONG / CORRECT

### 1. `@ManyToMany(cascade = ALL)` — deletes unrelated entities

WRONG — deleting one `Author` removes every shared `Book` and then every other `Author` of those books [a-beginners-guide-to-jpa-and-hibernate-cascade-types.md]:
```java
@Entity
public class Author {
    @ManyToMany(mappedBy = "authors", cascade = CascadeType.ALL)
    private Set<Book> books = new HashSet<>();
}

@Entity
public class Book {
    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "book_author",
        joinColumns = @JoinColumn(name = "book_id"),
        inverseJoinColumns = @JoinColumn(name = "author_id"))
    private Set<Author> authors = new HashSet<>();
}
```

CORRECT — cascade only PERSIST and MERGE; dissociate before deletion [a-beginners-guide-to-jpa-and-hibernate-cascade-types.md]:
```java
@Entity
public class Author {
    @ManyToMany(mappedBy = "authors",
        cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    private Set<Book> books = new HashSet<>();

    public void remove() {
        for (Book book : new HashSet<>(books)) {
            book.getAuthors().remove(this);
        }
        books.clear();
    }
}

@Entity
public class Book {
    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    @JoinTable(name = "book_author",
        joinColumns = @JoinColumn(name = "book_id"),
        inverseJoinColumns = @JoinColumn(name = "author_id"))
    private Set<Author> authors = new HashSet<>();
}
```

### 2. Unidirectional `@OneToMany` without `@JoinColumn` — extra join table

WRONG — Hibernate emits a redundant `post_post_comment` join table plus extra UPDATEs [the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate.md]:
```java
@Entity
public class Post {
    @Id @GeneratedValue
    private Long id;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PostComment> comments = new ArrayList<>();
}
```

CORRECT — pin the FK with `@JoinColumn`, or (preferred) make it bidirectional [the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate.md]:
```java
@Entity
public class Post {
    @Id @GeneratedValue
    private Long id;

    @OneToMany(
        mappedBy = "post",
        cascade = CascadeType.ALL,
        orphanRemoval = true)
    private List<PostComment> comments = new ArrayList<>();

    public void addComment(PostComment c) {
        comments.add(c);
        c.setPost(this);
    }
    public void removeComment(PostComment c) {
        comments.remove(c);
        c.setPost(null);
    }
}

@Entity
public class PostComment {
    @Id @GeneratedValue
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private Post post;
}
```

### 3. Missing bidirectional sync helpers — orphaned graph state

WRONG — caller has to remember to set both sides; if they forget, the in-memory graph disagrees with the DB after flush [jpa-hibernate-synchronize-bidirectional-entity-associations.md]:
```java
@Entity
public class Post {
    @OneToMany(mappedBy = "post",
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PostComment> comments = new ArrayList<>();

    public List<PostComment> getComments() { return comments; }
}

// Caller:
post.getComments().add(comment); // FK in DB ends up NULL
```

CORRECT — encapsulate both sides in `addComment`/`removeComment` [jpa-bidirectional-sync-methods.md]:
```java
@Entity
public class Post {
    @OneToMany(mappedBy = "post",
               cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PostComment> comments = new ArrayList<>();

    public Post addComment(PostComment comment) {
        comments.add(comment);
        comment.setPost(this);
        return this;
    }
    public Post removeComment(PostComment comment) {
        comments.remove(comment);
        comment.setPost(null);
        return this;
    }
}
```

### 4. `equals`/`hashCode` on mutable `@Id`

WRONG — auto-generated id is null before flush; the transient and managed instances land in different hash buckets and `Set.contains` lies [hibernate-facts-equals-and-hashcode.md]:
```java
@Entity
public class PostComment {
    @Id @GeneratedValue
    private Long id;

    @Override public int hashCode() { return Objects.hash(id); }
    @Override public boolean equals(Object o) {
        if (!(o instanceof PostComment)) return false;
        return Objects.equals(id, ((PostComment) o).id);
    }
}
```

CORRECT — guard the null id and return a constant class-based hashCode so the bucket is stable across state transitions [set-bidirectional-onetomany.md, hibernate-facts-equals-and-hashcode.md]:
```java
@Entity
public class PostComment {
    @Id @GeneratedValue
    private Long id;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PostComment)) return false;
        PostComment other = (PostComment) o;
        return id != null && id.equals(other.id);
    }
    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
```

### 5. `List` for `@ManyToMany` — delete-and-reinsert on every change

WRONG — removing one tag rewrites every `post_tag` row for that post [the-best-way-to-use-the-manytomany-annotation-with-jpa-and-hibernate.md]:
```java
@Entity
public class Post {
    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    @JoinTable(name = "post_tag",
        joinColumns = @JoinColumn(name = "post_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private List<Tag> tags = new ArrayList<>();
}
```

CORRECT — use `Set`; Hibernate now issues a single targeted DELETE [the-best-way-to-use-the-manytomany-annotation-with-jpa-and-hibernate.md]:
```java
@Entity
public class Post {
    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    @JoinTable(name = "post_tag",
        joinColumns = @JoinColumn(name = "post_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new HashSet<>();

    public void addTag(Tag tag)    { tags.add(tag);    tag.getPosts().add(this); }
    public void removeTag(Tag tag) { tags.remove(tag); tag.getPosts().remove(this); }
}
```

### 6. Missing `mappedBy` — duplicate FK ownership

WRONG — both sides own the relation; Hibernate emits an extra join table or duplicate UPDATEs [the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate.md]:
```java
@Entity
public class Post {
    @OneToMany(cascade = CascadeType.ALL)
    private List<PostComment> comments = new ArrayList<>();
}

@Entity
public class PostComment {
    @ManyToOne
    private Post post;
}
```

CORRECT — declare `mappedBy` so the `@ManyToOne` side is the sole owner [the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate.md]:
```java
@Entity
public class Post {
    @OneToMany(
        mappedBy = "post",
        cascade = CascadeType.ALL,
        orphanRemoval = true)
    private List<PostComment> comments = new ArrayList<>();
}

@Entity
public class PostComment {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private Post post;
}
```

### 7. Cascade on `@ManyToOne` — child mutates the parent

WRONG — saving a comment can re-persist or delete the post; the child should never drive parent life-cycle [a-beginners-guide-to-jpa-and-hibernate-cascade-types.md]:
```java
@Entity
public class PostComment {
    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "post_id")
    private Post post;
}
```

CORRECT — no cascade on the child-to-parent side [manytoone-jpa-hibernate.md]:
```java
@Entity
public class PostComment {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private Post post;
}
```

### 8. Missing `orphanRemoval` — dissociated child stays alive

WRONG — `removeComment` only nulls the FK; the row lingers forever [orphanremoval-jpa-hibernate.md]:
```java
@Entity
public class Post {
    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL)
    private List<PostComment> comments = new ArrayList<>();
}
```

CORRECT — add `orphanRemoval = true` so dissociation triggers DELETE [orphanremoval-jpa-hibernate.md]:
```java
@Entity
public class Post {
    @OneToMany(
        mappedBy = "post",
        cascade = CascadeType.ALL,
        orphanRemoval = true)
    private List<PostComment> comments = new ArrayList<>();
}
```

### 9. Default EAGER on `@OneToOne` — silent N+1

WRONG — both sides default to EAGER; even on the child side without `@MapsId`, every parent fetch triggers a secondary SELECT [best-way-onetoone-optional.md, the-best-way-to-map-a-onetoone-relationship-with-jpa-and-hibernate.md]:
```java
@Entity
public class Post {
    @OneToOne(mappedBy = "post", cascade = CascadeType.ALL)
    private PostDetails details;
}

@Entity
public class PostDetails {
    @Id @GeneratedValue
    private Long id;

    @OneToOne
    @JoinColumn(name = "post_id")
    private Post post;
}
```

CORRECT — share the PK with `@MapsId`, mark LAZY, and set `optional = false` on the parent side to let Hibernate skip the secondary SELECT [best-way-onetoone-optional.md, the-best-way-to-map-a-onetoone-relationship-with-jpa-and-hibernate.md]:
```java
@Entity
public class Post {
    @Id @GeneratedValue
    private Long id;

    @OneToOne(
        mappedBy = "post",
        cascade = CascadeType.ALL,
        fetch = FetchType.LAZY,
        optional = false)
    private PostDetails details;

    public void setDetails(PostDetails details) {
        if (details == null) {
            if (this.details != null) this.details.setPost(null);
        } else {
            details.setPost(this);
        }
        this.details = details;
    }
}

@Entity
public class PostDetails {
    @Id
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "id")
    private Post post;
}
```

### 10. `@ManyToMany` with extra columns — payload lost

WRONG — `@ManyToMany` cannot carry a `createdOn` column [the-best-way-to-map-a-many-to-many-association-with-extra-columns-when-using-jpa-and-hibernate.md]:
```java
@Entity
public class Post {
    @ManyToMany
    @JoinTable(name = "post_tag",
        joinColumns = @JoinColumn(name = "post_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new HashSet<>();
}
```

CORRECT — promote the join row to a link entity with an `@EmbeddedId` and two `@ManyToOne`s [the-best-way-to-map-a-many-to-many-association-with-extra-columns-when-using-jpa-and-hibernate.md]:
```java
@Embeddable
public class PostTagId implements Serializable {
    @Column(name = "post_id") private Long postId;
    @Column(name = "tag_id")  private Long tagId;

    @Override public boolean equals(Object o) { /* by both fields */ return true; }
    @Override public int hashCode() { return Objects.hash(postId, tagId); }
}

@Entity
public class PostTag {
    @EmbeddedId
    private PostTagId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("postId")
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("tagId")
    private Tag tag;

    @Column(name = "created_on")
    private LocalDateTime createdOn = LocalDateTime.now();
}
```

## Performance Pitfalls

- `FetchType.EAGER` cannot be undone at query time — every JPQL/Criteria query for the entity drags the eager graph along, often as N+1 secondary SELECTs rather than joins [manytoone-jpa-hibernate.md, jpa-association-fetching-validator.md].
- Parent side of `@OneToOne` ignores `FetchType.LAZY` without `@MapsId` or bytecode enhancement — Hibernate must issue a secondary SELECT to decide between `null` and a proxy [the-best-way-to-map-a-onetoone-relationship-with-jpa-and-hibernate.md, best-way-onetoone-optional.md].
- `@ManyToMany` with `List` deletes the whole join slice and re-inserts the survivors on every add/remove — switch to `Set` (or `SortedSet` with `@SortNatural`) [the-best-way-to-use-the-manytomany-annotation-with-jpa-and-hibernate.md].
- Unidirectional `@OneToMany` (no `mappedBy`, no `@JoinColumn`) creates a redundant join table and emits INSERT-then-UPDATE per child because the child entity is flushed before the collection [the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate.md].
- `@ElementCollection` without `@OrderColumn` re-creates the entire collection table on any change — add `@OrderColumn` to enable the id-bag-style targeted UPDATE/DELETE path [how-to-optimize-unidirectional-collections-with-jpa-and-hibernate.md].
- `Set<ChildEntity>` with a constant `hashCode` plus auto-id `equals` collapses into a single bucket — slow `add`/`contains`; this is why `List` is the right choice for bidirectional `@OneToMany` [set-bidirectional-onetomany.md].
- Bulk `CascadeType.REMOVE` on large collections issues one DELETE per child — for mass cleanup, use bulk JPQL DELETEs or DB-level `ON DELETE CASCADE` instead [cascade-delete-unidirectional-associations-spring.md, orphanremoval-jpa-hibernate.md].
- `@JoinFormula` JOIN ON expressions are not free — wrap the expression with a function-based index in the database or each lookup runs a sequential scan [how-to-customize-an-entity-association-join-on-clause-with-hibernate-joinformula.md].
- `@PrePersist`/`@PreUpdate` listeners on `@Embeddable` audit types run on every flush — keep them allocation-free; avoid loading collections from inside listeners [prepersist-preupdate-embeddable-jpa-hibernate.md].
- Use a `PostLoadEventListener`-based fetch validator in tests to catch silent EAGER joins and N+1 fetches before they ship [jpa-association-fetching-validator.md].

## Citations

- `a-beginners-guide-to-jpa-and-hibernate-cascade-types.md` — full tour of CascadeType semantics and the `@ManyToMany` REMOVE trap.
- `best-way-onetoone-optional.md` — why `optional = false` only avoids the secondary SELECT when combined with `@MapsId`.
- `cascade-delete-hibernate-events.md` — how cascade delete is realised by Hibernate event listeners.
- `cascade-delete-unidirectional-associations-spring.md` — pattern for cascading DELETE without bidirectional mappings using a custom Spring Data repository.
- `hibernate-facts-equals-and-hashcode.md` — business-key vs identifier strategies for equals/hashCode and the all-states-equality rule.
- `how-to-customize-an-entity-association-join-on-clause-with-hibernate-joinformula.md` — `@JoinFormula` for ad-hoc JOIN ON expressions when no FK column exists.
- `how-to-map-a-jpa-manytoone-relationship-to-a-sql-query-using-the-hibernate-joinformula-annotation.md` — mapping `@ManyToOne` through a raw SQL expression with `@JoinFormula`.
- `how-to-optimize-unidirectional-collections-with-jpa-and-hibernate.md` — `@OrderColumn`/id-bag optimisation for `@ElementCollection` and unidirectional bags.
- `jpa-association-fetching-validator.md` — building a `PostLoadEventListener` validator that flags EAGER joins and N+1 secondary queries.
- `jpa-bidirectional-sync-methods.md` — canonical `addX`/`removeX`/`setX` patterns for `@OneToMany`, `@OneToOne`, and `@ManyToMany`.
- `jpa-hibernate-synchronize-bidirectional-entity-associations.md` — why both sides must be kept in sync and what breaks when they are not.
- `manytoone-jpa-hibernate.md` — `@ManyToOne` defaults, why to set `FetchType.LAZY`, and `JOIN FETCH` to avoid N+1.
- `orphanremoval-jpa-hibernate.md` — `orphanRemoval` vs `CascadeType.REMOVE` semantics and why neither belongs on `@ManyToMany`.
- `prepersist-preupdate-embeddable-jpa-hibernate.md` — using JPA event listeners on `@Embeddable` audit types since Hibernate 5.2.17.
- `set-bidirectional-onetomany.md` — why `List` outperforms `Set` for bidirectional `@OneToMany` collections.
- `the-best-way-to-map-a-many-to-many-association-with-extra-columns-when-using-jpa-and-hibernate.md` — link-entity pattern with `@EmbeddedId` and `@MapsId` for payload columns.
- `the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate.md` — unidirectional vs bidirectional `@OneToMany`, `@JoinColumn` workaround, and `@ManyToOne`-only alternative.
- `the-best-way-to-map-a-onetoone-relationship-with-jpa-and-hibernate.md` — `@MapsId` as the most efficient `@OneToOne` mapping and the bidirectional EAGER pitfall.
- `the-best-way-to-use-the-manytomany-annotation-with-jpa-and-hibernate.md` — `Set` over `List` for `@ManyToMany` and avoiding `CascadeType.REMOVE`.
