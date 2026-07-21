---
name: spring-data-r2dbc
description: >
  Use when creating repositories, entities, database queries, CRUD operations,
  or data access code in a Spring Boot WebFlux project. Applies to tasks involving
  database tables, SQL migrations, saving or querying data, connecting to PostgreSQL,
  or implementing persistence logic with reactive types (Mono/Flux).
---

Follow the below principles when using Spring Data R2DBC:

1. **Use R2DBC annotations only, never JPA.**
   Use `@Table` from `org.springframework.data.relational.core.mapping`, NOT `javax.persistence` / `jakarta.persistence`.
   Use `@Id` from `org.springframework.data.annotation`.
   Never use `@Entity`, `@GeneratedValue`, `@OneToMany`, `@ManyToOne`, or any JPA annotation.

2. **Extend `ReactiveCrudRepository<T, ID>` or `ReactiveSortingRepository<T, ID>`.**
   All repository methods return `Mono<T>` or `Flux<T>`.
   Never use `JpaRepository`, `CrudRepository`, or any blocking repository interface.

3. **No lazy loading, no fetch joins, no OSIV.**
   R2DBC has no ORM session, no entity manager, no proxied collections.
   Relationships must be handled explicitly via separate queries composed with `flatMap`/`zip` in the service layer.
   Never model `@OneToMany` / `@ManyToOne` — they do not exist in R2DBC.

4. **Custom queries with `@Query` using native SQL and `:param` bind markers.**
   Use `@Query("SELECT ... FROM table WHERE col = :paramName")` with named parameters.
   For PostgreSQL you can also use `$1`, `$2` positional markers.
   For modifying queries, annotate with both `@Modifying` and `@Query`.

4a. **Use SQL arithmetic for concurrent field updates, not read-modify-write.**
   When incrementing or accumulating values (e.g. order totals), use `UPDATE ... SET col = col + :delta` in a `@Modifying @Query` method. A read-modify-write pattern (`findById` → mutate → `save`) loses updates under concurrency because the last writer wins. SQL `col = col + :delta` is atomic at the row level and avoids the need for `@Version` optimistic locking on simple accumulation.

5. **ID generation — use database auto-increment.**
   Use `BIGSERIAL` (PostgreSQL) for the primary key column.
   Spring Data R2DBC auto-populates the `@Id` field after insert when the ID is `null` or `0`.
   For non-auto-generated IDs, implement `Persistable<ID>` to control new-entity detection.

6. **Use `R2dbcEntityTemplate` for dynamic or complex queries.**
   When queries cannot be expressed as derived query methods or static `@Query`, use `R2dbcEntityTemplate`
   with its fluent API: `template.select(Post.class).matching(query(where("status").is("PUBLISHED"))).all()`.

7. **Optimistic locking with `@Version`. only if needed , not default**
   Annotate a `Long` field with `@Version` from `org.springframework.data.annotation`.
   Spring Data increments the version on every update and throws `OptimisticLockingFailureException` on conflicts.

8. **Auditing with `@EnableR2dbcAuditing`.**
   Use `@CreatedDate`, `@LastModifiedDate` from `org.springframework.data.annotation`.
   Register a `ReactiveAuditorAware<T>` bean for creator/modifier tracking.
9. **Keep audit timestamps in a shared base class.**
   If multiple entities contain `created_date` and `updated_date`, define them once in a common `BaseEntity`
   with `@Column("created_date")` and `@Column("updated_date")`, then let concrete entities extend it.
   Keep entity builders compatible with inherited fields (for Lombok, prefer `@SuperBuilder` on base and child classes).
  
## Entity Example

```java
@Table("posts")
public class Post {

    @Id
    private Long id;

    private String title;

    @Column("body")
    private String content;

    private String status;

    @Version #if needed 
    private Long version;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    // constructors, getters, setters
}

## Repository Example

```java
public interface PostRepository extends ReactiveCrudRepository<Post, Long> {

    Flux<Post> findByStatus(String status);

    Mono<Post> findByTitle(String title);

    @Query("SELECT * FROM posts WHERE title ILIKE :keyword OR body ILIKE :keyword")
    Flux<Post> search(@Param("keyword") String keyword);

    @Modifying
    @Query("UPDATE posts SET status = :status WHERE id = :id")
    Mono<Integer> updateStatus(@Param("id") Long id, @Param("status") String status);

    @Modifying
    @Query("DELETE FROM posts WHERE status = :status")
    Mono<Integer> deleteByStatus(@Param("status") String status);
}
```

## Loading Related Entities Example

R2DBC does not support joins or nested entity graphs. Load parent and children
with separate queries, then compose them reactively in the service layer:

```java
// CommentRepository.java
public interface CommentRepository extends ReactiveCrudRepository<Comment, Long> {

    Flux<Comment> findByPostId(Long postId);
}

// PostService.java — loading a post with its comments
public Mono<PostWithComments> findPostWithComments(Long postId) {
    Mono<Post> postMono = postRepository.findById(postId);
    Flux<Comment> commentsFlux = commentRepository.findByPostId(postId);

    return postMono
        .zipWith(commentsFlux.collectList())
        .map(tuple -> new PostWithComments(tuple.getT1(), tuple.getT2()))
        .switchIfEmpty(Mono.error(new ResourceNotFoundException("Post", postId)));
}
```

## R2dbcEntityTemplate Dynamic Query Example

```java
import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

// Find published posts by a keyword, ordered by creation date
Flux<Post> results = template.select(Post.class)
    .matching(query(where("status").is("PUBLISHED")
        .and("title").like("%" + keyword + "%"))
        .sort(Sort.by(Sort.Direction.DESC, "created_at"))
        .limit(20))
    .all();
```
