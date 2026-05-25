---
name: jpa-hibernate-best-practices
description: Authoritative best-practices reference for JPA, Hibernate, Spring Data JPA, and Java persistence. Use when writing or reviewing @Entity classes, JPA/Hibernate mappings, JPQL/HQL/Criteria/native queries, Spring Data repositories, @Transactional methods, batch jobs, connection-pool config, cache config, or MongoDB persistence. Also triggers on: LazyInitializationException, N+1 problem, MultipleBagFetchException, OptimisticLockException, HHH000104, open-in-view, CascadeType, FetchType, GenerationType, @Version, EntityManager, SessionFactory, HikariCP, FlexyPool, Hibernate Envers, Debezium, hypersistence-utils.
---

# JPA / Hibernate Best Practices

You hold curated rules distilled from Vlad Mihalcea's 500+ articles on Java persistence. The rules are not in this file — they live in `references/`, one file per topic. **Use the topic index below to load the 1-3 references relevant to the task. Do not load all of them.**

## How to use this skill

1. Read the **Topic index** and pick the reference(s) that match the user's code or question.
2. Open those `references/<topic>.md` files with the Read tool.
3. Apply the principles, cite the source article slug when explaining a rule, and produce code that matches the **CORRECT** side of the WRONG/CORRECT pairs in the reference.
4. When in doubt between two topics (e.g., a question about cascade *and* fetching), load both references.

## Topic index

| Reference | When to load |
| --- | --- |
| `fetching-and-n-plus-one.md` | Anything about `FetchType`, `JOIN FETCH`, `@EntityGraph`, N+1, `LazyInitializationException`, `MultipleBagFetchException`, OSIV, DTO projections, pagination with collections |
| `cascade-and-associations.md` | `@OneToMany`, `@ManyToOne`, `@OneToOne`, `@ManyToMany`, `CascadeType`, `orphanRemoval`, bidirectional sync, `mappedBy`, `addX`/`removeX` helpers, `equals`/`hashCode` on entities |
| `locking-and-concurrency.md` | `@Version`, `LockModeType`, OPTIMISTIC / PESSIMISTIC, OptimisticLockException, lost update, deadlock, SKIP LOCKED job queues, write skew |
| `transactions-and-isolation.md` | `@Transactional`, propagation, isolation levels (RC/RR/SERIALIZABLE/SNAPSHOT), `readOnly`, REQUIRES_NEW vs REQUIRED, MVCC vs 2PL, JTA vs RESOURCE_LOCAL, replica routing |
| `flush-and-dirty-checking.md` | FlushMode, auto-flush, persist vs merge vs save, entity-state transitions, `@DynamicUpdate`, bytecode-enhanced dirty checking |
| `batch-operations.md` | Bulk insert/update/delete, `hibernate.jdbc.batch_size`, `order_inserts`, `StatelessSession`, IDENTITY vs SEQUENCE for batching, `saveAll` in Spring Data |
| `identifiers.md` | `@Id`, `@GeneratedValue`, IDENTITY / SEQUENCE / TABLE / UUID / TSID / hi-lo, `@EmbeddedId`, `@IdClass`, `@MapsId`, composite keys, natural keys |
| `caching.md` | 1LC vs 2LC, CacheConcurrencyStrategy (READ_ONLY / NONSTRICT_READ_WRITE / READ_WRITE / TRANSACTIONAL), query cache, collection cache, eviction |
| `connection-pooling.md` | HikariCP, FlexyPool, pool sizing (Little's Law), `LazyConnectionDataSourceProxy`, `provider_disables_autocommit`, leak detection, replica routing |
| `inheritance-mapping.md` | `@Inheritance`, SINGLE_TABLE / JOINED / TABLE_PER_CLASS, `@MappedSuperclass`, `@DiscriminatorColumn`, polymorphic queries |
| `type-mapping.md` | Custom types: JSON/JSONB (hypersistence-utils), Postgres arrays/hstore/interval, enum mapping, `java.time`, `AttributeConverter`, `@Formula`, `@Generated`, `@ColumnTransformer` |
| `sql-performance.md` | JPQL/HQL/Criteria/native, indexes, execution plans, pagination (offset vs keyset), EXISTS vs IN, query plan cache, `StatementInspector`, joins, DB-specific tuning |
| `spring-data-jpa.md` | `JpaRepository`, `@Query`, derived queries, Specification, projections, `Pageable`, `saveAll`, `BaseJpaRepository` (hypersistence-utils), `open-in-view` |
| `audit-and-cdc.md` | Envers, `@PostUpdate`, DB triggers, Debezium, outbox pattern, temporal tables, soft-delete + audit interaction |
| `advanced-orm.md` | Multitenancy, soft delete, `@Immutable`, equals/hashCode on entities, integration testing (Testcontainers), bytecode enhancement, naming strategy, schema validation, `enable_lazy_load_no_trans` anti-pattern, JPA proxies, recursive associations |
| `mongodb.md` | MongoDB aggregation, indexing, embed vs reference, optimistic locking, bulk writes, write concerns, time-series patterns |

## Universal decision trees

These decide which topic to focus on; load the matching reference for full code.

**"I'm mapping an association."**
```
Is it *ToMany?
├─ Yes → cascade-and-associations.md (esp. @ManyToMany cascade rules) + fetching-and-n-plus-one.md
└─ No  (*ToOne) → cascade-and-associations.md (force LAZY!) + identifiers.md (@MapsId for @OneToOne)
```

**"I'm hitting LazyInitializationException."**
```
Don't enable open-in-view or enable_lazy_load_no_trans (both anti-patterns).
Load fetching-and-n-plus-one.md and pick: @EntityGraph, JOIN FETCH in JPQL, DTO projection, or refactor the boundary so all access happens inside the transaction.
```

**"I'm inserting many rows."**
```
> 100 rows? → batch-operations.md
Uses GenerationType.IDENTITY? → JDBC batching is disabled — switch to SEQUENCE (identifiers.md).
Cascading required? → entity batch with flush()/clear() cadence. Else StatelessSession.
```

**"I'm fetching a collection with pagination."**
```
Don't combine Pageable + JOIN FETCH on a *ToMany — Hibernate logs HHH000104 and paginates in memory.
Two-step pattern: page the parent IDs (keyset preferred), then JOIN FETCH children by ID.
See fetching-and-n-plus-one.md + sql-performance.md.
```

**"I need to lock a row."**
```
Low write contention? → locking-and-concurrency.md → @Version + retry on OptimisticLockException
High contention or job queue? → PESSIMISTIC_WRITE + (optionally) SKIP LOCKED
Parent must reflect child change? → OPTIMISTIC_FORCE_INCREMENT on parent
```

**"I'm choosing an isolation level."**
```
transactions-and-isolation.md decision tree — pick by anomaly tolerance (dirty / non-repeatable / phantom / write-skew), not by database default.
```

## Universal red flags (always investigate)

When you see any of these in code or config, stop and check the cited reference:

- `FetchType.EAGER` on `@OneToMany` or `@ManyToMany` → `fetching-and-n-plus-one.md`
- `CascadeType.ALL` or `CascadeType.REMOVE` on `@ManyToMany` → `cascade-and-associations.md`
- `@OneToMany` (default unidirectional, no `@JoinColumn`) → creates extra join table → `cascade-and-associations.md`
- `@Enumerated(EnumType.ORDINAL)` → `type-mapping.md`
- `spring.jpa.open-in-view=true` (Spring Boot default) → `spring-data-jpa.md` + `fetching-and-n-plus-one.md`
- `hibernate.enable_lazy_load_no_trans=true` → anti-pattern → `advanced-orm.md`
- `GenerationType.IDENTITY` on Postgres/Oracle in a batch-insert path → `identifiers.md` + `batch-operations.md`
- `@Transactional` without `readOnly=true` on a query-only method → `transactions-and-isolation.md`
- `equals` / `hashCode` on an `@Entity` using a mutable field or a generated `@Id` → `cascade-and-associations.md`
- `LazyInitializationException` in logs → `fetching-and-n-plus-one.md` (fix the boundary, don't enable OSIV)
- `MultipleBagFetchException` → `fetching-and-n-plus-one.md` (use Set, or two queries)
- HikariCP `maximum-pool-size` > 2*cores + spindle_count without measurement → `connection-pooling.md`
- Loop with `entityManager.persist()` and no `flush()/clear()` cadence → `batch-operations.md`
- `Page<Entity>` returned directly to a REST controller → `spring-data-jpa.md`

## Citation style

When explaining a rule, cite the source article by slug, e.g.: "Per `eager-fetching-is-a-code-smell.md`, always set `fetch = FetchType.LAZY` on `@ManyToOne`." The slugs in the references map to the article archive; the user can read the full article if they want background.

## Scope notes

- Java-only. SQL examples are in JPQL/HQL or DB-specific dialect, not framework-agnostic ORM pseudocode.
- For pure SQL/security review (injection, schema, grants), use the `sql-code-review` skill instead.
- For a JPA codebase audit (running the rules over actual files), use the companion `jpa-hibernate-code-review` skill — it walks a checklist and produces structured findings.
