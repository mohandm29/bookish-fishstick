# Entity Identifiers and ID Generation

## Core Principles

- Never combine `GenerationType.IDENTITY` with bulk inserts: Hibernate disables JDBC batching for `INSERT` statements when the identifier comes from an auto-incremented column, because it must execute each `INSERT` individually to read back the generated key [`why-you-should-never-use-the-table-identifier-generator-with-jpa-and-hibernate.md`, `database-primary-key-flavors.md`].
- Prefer `GenerationType.SEQUENCE` on databases that support sequences (PostgreSQL, Oracle, SQL Server 2012+, DB2): sequences let Hibernate know the id before flush, enabling JDBC batching and the pooled / pooled-lo optimizers [`hibernate-identity-sequence-and-table-sequence-generator.md`, `jpa-entity-identifier-sequence.md`].
- Never use `GenerationType.TABLE` in modern code; the row-level lock plus a forced separate transaction serializes id generation and ruins throughput, even with a pooled optimizer [`why-you-should-never-use-the-table-identifier-generator-with-jpa-and-hibernate.md`].
- Set `allocationSize` on every `@SequenceGenerator` and make the database sequence's `INCREMENT BY` match it; Hibernate 5+ uses the `pooled` optimizer automatically when `allocationSize > 1` [`migrate-hilo-hibernate-pooled.md`, `hibernate-identity-sequence-and-table-sequence-generator.md`].
- Avoid the legacy `hilo` optimizer for new mappings: the database sequence value is a bucket number, not a real id, so any external writer that does not understand hi/lo will clash. Migrate to `pooled` or `pooled-lo` [`migrate-hilo-hibernate-pooled.md`, `the-hilo-algorithm.md`].
- Do not store a random `java.util.UUID` (RFC 4122 v4) as a primary key on a clustered-index engine (MySQL InnoDB, SQL Server): random inserts cause B+Tree page splits, fragment the clustered index, and bloat secondary indexes that carry the PK [`uuid-database-primary-key.md`, `database-primary-key-flavors.md`].
- Prefer a 64-bit time-sorted identifier (TSID) over a 128-bit UUID when you need application-generated ids; TSID is half the size, monotonically increasing, and indexes like a `bigint` [`tsid-identifier-jpa-hibernate.md`, `uuid-database-primary-key.md`].
- Use natural keys with caution: any future change to the uniqueness rule (a country's social-security format, an ISBN reissue) forces updates to the PK and every FK that references it. A surrogate + `@NaturalId` is almost always safer than a natural PK [`database-primary-key-flavors.md`, `the-best-way-to-map-a-naturalid-business-key-with-jpa-and-hibernate.md`].
- For composite keys, use `@EmbeddedId` (preferred) or `@IdClass`; the embeddable must be `Serializable` and must implement `equals` / `hashCode` from the column values, not from generated state [`the-best-way-to-map-a-composite-primary-key-with-jpa-and-hibernate.md`].
- Use `@MapsId` to share an entity's id with the id of its `@OneToOne` parent (the child reuses the parent's PK as both PK and FK); this is the canonical one-to-one PK-sharing pattern [`change-one-to-one-primary-key-column-jpa-hibernate.md`].
- Implement `equals` / `hashCode` so the entity stays in a `Set` across the state transitions transient -> persistent -> detached; if a `@NaturalId` exists, use it; otherwise use the assigned/generated id with a constant `hashCode` and an id-null-safe `equals` [`how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier.md`].
- `GenerationType.AUTO` is dialect-dependent: in Hibernate 5+ it picks `SEQUENCE` on Oracle/PostgreSQL/DB2, `IDENTITY` on MySQL/SQL Server, and a special UUID strategy when the id type is `java.util.UUID`. Pin a strategy explicitly when behaviour matters [`uuid-identifier-jpa-hibernate.md`, `why-you-should-never-use-the-table-identifier-generator-with-jpa-and-hibernate.md`].
- Define one sequence per aggregate root rather than reusing the global `hibernate_sequence`; this lets each entity tune its own `allocationSize` and keeps id streams independent for partitioning/archival [`jpa-entity-identifier-sequence.md`, `migrate-hilo-hibernate-pooled.md`].
- For a many-to-one association keyed on a non-PK column, use `@JoinColumn(referencedColumnName = "uniqueCol")` plus a unique constraint; the referenced column must be `@NaturalId` or carry a `UNIQUE` constraint to satisfy referential integrity [`how-to-map-a-manytoone-association-using-a-non-primary-key-column.md`].
- Custom string-formatted ids (e.g. `INV-2026-0001`) belong in a custom `IdentifierGenerator` driven by a numeric sequence; never derive the prefix from mutable business data and never compute the formatted id inside `equals` [`how-to-implement-a-custom-string-based-sequence-identifier-generator-with-hibernate.md`].
- The `@GeneratorType` Hibernate annotation (and Hibernate 6 `@ValueGenerationType`) generates non-id columns (`createdBy`, `lastModifiedBy`); do not abuse it for identifier generation -- use `IdentifierGenerator` for the PK so the id is available before flush [`how-to-emulate-createdby-and-lastmodifiedby-from-spring-data-using-the-generatortype-hibernate-annotation.md`].

## Decision Trees

### Choosing a generator strategy

- Is the id type `UUID` or do you require application-generated ids?
  - Yes, and you control the schema -> use `@Tsid` (Hypersistence Utils) on a `Long` column. Half the bytes of a UUID, time-sorted, plays well with B+Tree clustered indexes [`tsid-identifier-jpa-hibernate.md`, `uuid-database-primary-key.md`].
  - Yes, and you must keep `UUID` for external compatibility -> use UUIDv7 (time-sorted) or generate via database function (`uuid_generate_v4()` only if your index is not clustered on the PK) [`uuid-identifier-jpa-hibernate.md`, `hibernate-and-uuid-identifiers.md`].
- Is the database PostgreSQL, Oracle, DB2, SQL Server 2012+, or H2?
  - Yes -> `GenerationType.SEQUENCE` with `allocationSize >= 50` and the default `pooled` optimizer; this enables JDBC batching [`jpa-entity-identifier-sequence.md`, `hibernate-batch-sequence-generator.md`].
- Is the database MySQL or another engine without sequence support?
  - You can tolerate no batching on inserts -> `GenerationType.IDENTITY` (auto-increment). Hibernate must execute each `INSERT` separately to read the generated key [`hibernate-identity-sequence-and-table-sequence-generator.md`].
  - You need batched inserts on MySQL -> assign the id in the application (TSID or pre-generated sequence value) and use the `assigned` strategy [`how-to-combine-the-hibernate-assigned-generator-with-a-sequence-or-an-identity-column.md`].
- Are you running Hibernate against multiple databases (Oracle dev, PostgreSQL prod, MySQL test)?
  - Use `GenerationType.SEQUENCE` everywhere and fall back to a Hibernate dialect that emulates sequences via tables only where unavoidable. Never use `GenerationType.TABLE` for portability [`how-to-replace-the-table-identifier-generator-with-either-sequence-or-identity-in-a-portable-way.md`, `why-you-should-never-use-the-table-identifier-generator-with-jpa-and-hibernate.md`].

### Distributed system or multi-writer scenario?

- Multiple application instances writing concurrently, ids must not collide -> TSID with a per-node bit allocation (1-8 node bits via `TSID.Factory.builder().withNodeBits(n)`), or UUIDv7 [`tsid-identifier-jpa-hibernate.md`, `uuid-database-primary-key.md`].
- External system (ETL, replication tool) also inserts into the same table -> use `pooled` (boundary-based) instead of `hilo` (bucket-based); `pooled` records the upper bound in the sequence, so external clients can call `NEXT VALUE FOR seq` safely [`hibernate-batch-sequence-generator.md`, `migrate-hilo-hibernate-pooled.md`].
- Ids leak to URLs and must be unguessable -> use TSID or UUID, not a bigint auto-increment; sequential integers expose row counts [`uuid-database-primary-key.md`].

### Composite key: `@EmbeddedId` vs `@IdClass` vs `@MapsId`

- All key columns are simple scalars and you want the id to be a single addressable object (`employee.getId().getCompanyId()`) -> `@EmbeddedId` with an `@Embeddable` holding the columns. Preferred for new mappings [`the-best-way-to-map-a-composite-primary-key-with-jpa-and-hibernate.md`].
- You need the entity to expose the key columns as direct top-level fields (`employee.getCompanyId()`) for legacy DTO compatibility -> `@IdClass` (separate non-`@Embeddable` class mirroring the `@Id` fields on the entity) [`the-best-way-to-map-a-composite-primary-key-with-jpa-and-hibernate.md`].
- One of the key parts is itself a `@ManyToOne` association whose PK is the FK -> put the `@ManyToOne` inside the `@Embeddable` and annotate the relationship with `@MapsId` so Hibernate keeps the id in sync with the association [`the-best-way-to-map-a-composite-primary-key-with-jpa-and-hibernate.md`, `change-one-to-one-primary-key-column-jpa-hibernate.md`].
- One side of a `@OneToOne` shares the PK with the other -> child uses `@MapsId` on the `@OneToOne`; no separate FK column is added [`change-one-to-one-primary-key-column-jpa-hibernate.md`].

### String-formatted business id required (e.g. `ORD-0000001`)?

- The visible id format is derived from a numeric counter -> custom `IdentifierGenerator` that reads `NEXTVAL` and formats the string in `generate(...)`; keep a hidden numeric column for joins if performance matters [`how-to-implement-a-custom-string-based-sequence-identifier-generator-with-hibernate.md`].
- The format is purely cosmetic and only shown to users -> keep the PK numeric (TSID or sequence) and compute the display string in a `@Transient` getter; this avoids string PKs in every FK column [`how-to-implement-a-custom-string-based-sequence-identifier-generator-with-hibernate.md`, `uuid-database-primary-key.md`].

### Need a generated id but also want to override it sometimes (import, migration)?

- Yes -> the standard `@GeneratedValue` strategies always overwrite. Use the Hibernate `@GenericGenerator` `"assigned"` strategy with a custom `IdentifierGenerator` that falls back to a sequence/identity when the id is null [`how-to-combine-the-hibernate-assigned-generator-with-a-sequence-or-an-identity-column.md`].

## Anti-patterns: WRONG / CORRECT

### 1. IDENTITY on a sequence-capable database for batched inserts

WRONG -- Hibernate disables JDBC insert batching whenever the id comes from an `IDENTITY` column [`why-you-should-never-use-the-table-identifier-generator-with-jpa-and-hibernate.md`, `hibernate-identity-sequence-and-table-sequence-generator.md`]:

```java
// PostgreSQL with hibernate.jdbc.batch_size=50 set
@Entity
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // batching silently disabled
    private Long id;
}
```

CORRECT -- on PostgreSQL/Oracle/DB2/SQL Server, use a sequence so the id is known before flush and `INSERT`s batch normally [`jpa-entity-identifier-sequence.md`, `hibernate-batch-sequence-generator.md`]:

```java
@Entity
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post_seq")
    @SequenceGenerator(name = "post_seq", sequenceName = "post_seq", allocationSize = 50)
    private Long id;
}
```

### 2. `@GeneratedValue` on PostgreSQL without picking a strategy

WRONG -- on Hibernate 5/6 with PostgreSQL this resolves to `SEQUENCE` against the shared `hibernate_sequence` (or `hibernate_sequences` table on some dialects), preventing per-entity tuning [`jpa-entity-identifier-sequence.md`, `why-you-should-never-use-the-table-identifier-generator-with-jpa-and-hibernate.md`]:

```java
@Entity
public class Post {
    @Id
    @GeneratedValue // implicit AUTO -- shared global sequence, no allocationSize control
    private Long id;
}
```

CORRECT -- one sequence per aggregate root, explicit `allocationSize` matching the DB `INCREMENT BY` [`migrate-hilo-hibernate-pooled.md`]:

```java
@Entity
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post_seq")
    @SequenceGenerator(name = "post_seq", sequenceName = "post_seq", allocationSize = 50)
    private Long id;
}
// DDL: CREATE SEQUENCE post_seq START 1 INCREMENT 50;
```

### 3. Random UUIDv4 as the clustered PK

WRONG -- on MySQL InnoDB the PK is the clustered index. Random 128-bit inserts cause page splits and index fragmentation; secondary indexes embed the 16-byte PK and bloat [`uuid-database-primary-key.md`, `database-primary-key-flavors.md`]:

```java
@Entity
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id; // java.util.UUID.randomUUID(), v4 random
}
```

CORRECT -- TSID stored in a `bigint` column; monotonically increasing, half the size, no fragmentation [`tsid-identifier-jpa-hibernate.md`, `uuid-database-primary-key.md`]:

```java
@Entity
public class Post {
    @Id
    @Tsid // io.hypersistence.utils.hibernate.id.Tsid (Hibernate 6.3+)
    private Long id;
}
// DDL: CREATE TABLE post (id bigint NOT NULL PRIMARY KEY, title varchar(255));
```

### 4. `GenerationType.TABLE` in modern code

WRONG -- emulates a sequence using a row-level lock plus a forced separate transaction; serializes id generation [`why-you-should-never-use-the-table-identifier-generator-with-jpa-and-hibernate.md`]:

```java
@Entity
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.TABLE) // FOR UPDATE + extra TX on every batch
    private Long id;
}
```

CORRECT -- use `SEQUENCE` (or `IDENTITY` on MySQL); they use lightweight database-internal locks released immediately [`how-to-replace-the-table-identifier-generator-with-either-sequence-or-identity-in-a-portable-way.md`, `why-you-should-never-use-the-table-identifier-generator-with-jpa-and-hibernate.md`]:

```java
@Entity
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post_seq")
    @SequenceGenerator(name = "post_seq", sequenceName = "post_seq", allocationSize = 50)
    private Long id;
}
```

### 5. Mutable `@Id` field

WRONG -- primary keys must be `IMMUTABLE`; reassigning the id after persist desynchronizes the Persistence Context cache and breaks `equals` / `hashCode` and FK consistency [`database-primary-key-flavors.md`, `how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier.md`]:

```java
@Entity
public class Post {
    @Id @GeneratedValue private Long id;
    public void setId(Long id) { this.id = id; } // exposed setter, mutated after persist
}
```

CORRECT -- no public id setter; treat the id as write-once. Let the generator assign it [`database-primary-key-flavors.md`]:

```java
@Entity
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post_seq")
    @SequenceGenerator(name = "post_seq", sequenceName = "post_seq", allocationSize = 50)
    private Long id;
    public Long getId() { return id; } // no setter
}
```

### 6. Composite key without `equals` / `hashCode`

WRONG -- the JPA spec requires composite id classes to be `Serializable` with value-based `equals` / `hashCode`; without them `find(EmployeeId)`, second-level cache and Set membership all break [`the-best-way-to-map-a-composite-primary-key-with-jpa-and-hibernate.md`]:

```java
@Embeddable
public class EmployeeId { // missing Serializable, equals, hashCode
    @Column(name = "company_id")    private Long companyId;
    @Column(name = "employee_number") private Long employeeNumber;
}
```

CORRECT -- `Serializable` plus `equals` / `hashCode` derived from every column [`the-best-way-to-map-a-composite-primary-key-with-jpa-and-hibernate.md`]:

```java
@Embeddable
public class EmployeeId implements Serializable {
    @Column(name = "company_id")      private Long companyId;
    @Column(name = "employee_number") private Long employeeNumber;
    public EmployeeId() {}
    public EmployeeId(Long companyId, Long employeeNumber) {
        this.companyId = companyId; this.employeeNumber = employeeNumber;
    }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EmployeeId)) return false;
        EmployeeId that = (EmployeeId) o;
        return Objects.equals(companyId, that.companyId)
            && Objects.equals(employeeNumber, that.employeeNumber);
    }
    @Override public int hashCode() { return Objects.hash(companyId, employeeNumber); }
}
```

### 7. `equals` / `hashCode` based on a generated id

WRONG -- before `persist` the id is `null`, after `persist` it is assigned, so the bucket in a `HashSet` changes mid-transaction and `contains(entity)` returns `false` [`how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier.md`]:

```java
@Entity
public class Book {
    @Id @GeneratedValue private Long id;
    @Override public boolean equals(Object o) {
        if (!(o instanceof Book)) return false;
        return Objects.equals(id, ((Book) o).id); // id changes from null -> value
    }
    @Override public int hashCode() { return Objects.hash(id); } // hash changes!
}
```

CORRECT -- constant `hashCode`, null-safe id comparison; survives transient -> persisted -> detached -> merged [`how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier.md`]:

```java
@Entity
public class Book {
    @Id @GeneratedValue private Long id;
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Book)) return false;
        Book other = (Book) o;
        return id != null && id.equals(other.id);
    }
    @Override public int hashCode() { return getClass().hashCode(); }
}
```

### 8. `@SequenceGenerator` without `allocationSize` mismatched against the DB

WRONG -- application says `allocationSize = 1` while the DB sequence is `INCREMENT BY 50`, or vice versa; on Hibernate 5+ this raises `MappingException: The increment size of the [post_sequence] sequence is set to [N] in the entity mapping while the associated database sequence increment size is [M]` [`migrate-hilo-hibernate-pooled.md`]:

```java
@SequenceGenerator(name = "post_seq", sequenceName = "post_seq", allocationSize = 1)
// while DDL is: CREATE SEQUENCE post_seq START 1 INCREMENT 50;
```

CORRECT -- align both sides; the DDL `INCREMENT BY` equals the `allocationSize`, and the application gets `allocationSize - 1` ids per round trip [`migrate-hilo-hibernate-pooled.md`, `hibernate-batch-sequence-generator.md`]:

```java
@SequenceGenerator(name = "post_seq", sequenceName = "post_seq", allocationSize = 50)
// DDL: CREATE SEQUENCE post_seq START 1 INCREMENT 50;
```

### 9. `hilo` optimizer in a system with external writers

WRONG -- the database sequence value is the `hi` bucket, not the next id. Any client unaware of the hi/lo formula will collide [`migrate-hilo-hibernate-pooled.md`, `the-hilo-algorithm.md`]:

```java
@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post_seq")
@GenericGenerator(name = "post_seq", strategy = "sequence",
    parameters = {
        @Parameter(name = "sequence_name",  value = "post_seq"),
        @Parameter(name = "increment_size", value = "5"),
        @Parameter(name = "optimizer",      value = "hilo")
    })
private Long id;
```

CORRECT -- switch to `pooled` (or `pooled-lo`) and update the DB sequence so the sequence value is the actual upper bound of the next pool [`migrate-hilo-hibernate-pooled.md`, `hibernate-batch-sequence-generator.md`]:

```java
@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post_seq")
@SequenceGenerator(name = "post_seq", sequenceName = "post_seq", allocationSize = 5) // pooled is default
private Long id;
// Migration SQL (PostgreSQL):
//   SELECT setval('post_seq', (SELECT MAX(id) FROM post) + 1);
//   ALTER SEQUENCE post_seq INCREMENT BY 5;
```

### 10. Legacy `@GenericGenerator("uuid")` (UUIDHexGenerator)

WRONG -- the `"uuid"` strategy maps to `UUIDHexGenerator`, which produces a 32-char hex string (not a real UUID type) and is deprecated [`replace-deprecated-genericgenerator.md`, `hibernate-and-uuid-identifiers.md`]:

```java
@Id
@GeneratedValue(generator = "uuid")
@GenericGenerator(name = "uuid", strategy = "uuid") // legacy hex string
private String id;
```

CORRECT -- on Hibernate 6 use the JPA-standard form; on Hibernate 5 use `"uuid2"` (RFC 4122) [`hibernate-and-uuid-identifiers.md`, `uuid-identifier-jpa-hibernate.md`]:

```java
// Hibernate 6
@Id
@GeneratedValue(strategy = GenerationType.UUID)
private UUID id;

// Hibernate 5 fallback
@Id
@GeneratedValue(generator = "uuid2")
@GenericGenerator(name = "uuid2", strategy = "uuid2")
private UUID id;
```

### 11. Natural string key as the PK

WRONG -- a `CHAR(17)` VIN or a slug as the PK costs 17 bytes per FK row and is slow for index scans; every referencing table inherits the bloat [`database-primary-key-flavors.md`, `the-best-way-to-map-a-naturalid-business-key-with-jpa-and-hibernate.md`]:

```java
@Entity
public class Vehicle {
    @Id
    @Column(length = 17) // VIN as PK
    private String vin;
}
```

CORRECT -- surrogate numeric PK plus `@NaturalId` for lookups; gives compact joins and a Hibernate-aware lookup API (`bySimpleNaturalId`) [`the-best-way-to-map-a-naturalid-business-key-with-jpa-and-hibernate.md`, `database-primary-key-flavors.md`]:

```java
@Entity
public class Vehicle {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "vehicle_seq")
    @SequenceGenerator(name = "vehicle_seq", sequenceName = "vehicle_seq", allocationSize = 50)
    private Long id;

    @NaturalId
    @Column(length = 17, nullable = false, unique = true)
    private String vin;
}
// Lookup: session.bySimpleNaturalId(Vehicle.class).load(vin)
```

## Performance Pitfalls

- `IDENTITY` disables JDBC insert batching: Hibernate must execute every `INSERT` separately to retrieve the generated key via `Statement.getGeneratedKeys()`; on bulk-insert workloads this can be an order-of-magnitude slowdown vs `SEQUENCE` + pooled [`hibernate-identity-sequence-and-table-sequence-generator.md`, `database-primary-key-flavors.md`].
- Random UUIDv4 PKs fragment B+Tree clustered indexes (MySQL InnoDB, SQL Server) and defeat fill-factor pre-allocation, so insert throughput degrades and the index grows; secondary indexes that embed the 16-byte PK pay the cost too [`uuid-database-primary-key.md`, `database-primary-key-flavors.md`].
- `allocationSize = 1` (the JPA spec default) means one `NEXTVAL` round trip per `persist()`. With `allocationSize = 50` you reduce sequence round trips by 50x for batch jobs [`migrate-hilo-hibernate-pooled.md`, `hibernate-batch-sequence-generator.md`].
- `GenerationType.TABLE` requires a `SELECT ... FOR UPDATE` plus an `UPDATE` plus a separate suspended transaction for every batch; under contention it serializes inserts and exhausts the connection pool [`why-you-should-never-use-the-table-identifier-generator-with-jpa-and-hibernate.md`].
- `hilo` optimizer fetches an `incrementSize` block per sequence call but the sequence value is a bucket number; a third-party writer that does `INSERT ... VALUES (NEXTVAL(seq), ...)` will collide with the application until you migrate to `pooled` [`migrate-hilo-hibernate-pooled.md`, `the-hilo-algorithm.md`].
- 128-bit UUID PKs amplify storage cost across every FK column. If a `post_comment` table has FKs to `post`, `user`, and `tag`, switching from `UUID` to `TSID`/`bigint` saves 24 bytes per row in user data alone, plus equivalent savings on each index [`uuid-database-primary-key.md`].
- Non-numeric natural keys (`CHAR(N)`) are slower to compare and to hash in index lookups than `bigint`; the wider the key, the fewer entries fit per page and the deeper the B+Tree [`database-primary-key-flavors.md`].
- Mixing the `IDENTITY` generator with `hibernate.order_inserts=true` does not enable batching for inserts because batching is disabled at the persister level for `IDENTITY` regardless of ordering [`hibernate-identity-sequence-and-table-sequence-generator.md`].
- `@OneToOne` with a separate FK column instead of `@MapsId` doubles the joinable columns and forces an extra index; `@MapsId` shares the PK and is both denser and faster [`change-one-to-one-primary-key-column-jpa-hibernate.md`].
- Implementing `equals` using a generated id with a non-constant `hashCode` causes silent data loss in `Set` collections (e.g., `@OneToMany Set<Comment>`): items added before flush become unreachable after the id is assigned [`how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier.md`].
- `BatchSequenceGenerator` (custom) fetches N sequence values in one round trip using `SELECT NEXTVAL(seq) FROM generate_series(1, N)` on PostgreSQL or a recursive CTE on SQL Server; for jobs that insert thousands of rows this can outperform the default `pooled` optimizer because it avoids N separate `NEXTVAL` calls when refilling the pool [`hibernate-batch-sequence-generator.md`].
- Putting a `@ManyToOne` association into an `@Embeddable` `@EmbeddedId` removes the need for a separate `@MapsId` field on the entity, saving one column-to-field mapping per join and clarifying that the FK is part of the identity [`the-best-way-to-map-a-composite-primary-key-with-jpa-and-hibernate.md`].
- `@NaturalIdCache` plus `READ_WRITE` second-level cache turns a `bySimpleNaturalId` lookup into zero SQL after the entity is first persisted, beating a regular indexed lookup by avoiding the network round trip [`the-best-way-to-map-a-naturalid-business-key-with-jpa-and-hibernate.md`].
- Sequence-call cost is measurable: on PostgreSQL each `NEXTVAL` is one network round trip plus a small WAL write. For a 1000-row import with `allocationSize=1` that is 1000 round trips just for ids; with `allocationSize=50` it is 20. With `BatchSequenceGenerator` allocating the full pool in one query, it is 1 [`hibernate-batch-sequence-generator.md`, `jpa-entity-identifier-sequence.md`].
- `@EmbeddedId` containing a `@ManyToOne` causes Hibernate to issue an extra `SELECT` to materialize the parent during `find(EmbeddableId)` unless the parent is already in the Persistence Context; mark the association `LAZY` and avoid traversing it in `equals`/`hashCode` [`the-best-way-to-map-a-composite-primary-key-with-jpa-and-hibernate.md`].
- A `byte[]` UUID stored as `BINARY(16)` is half the disk footprint of `CHAR(36)` and twice as fast to compare in an index seek; never store a UUID as `VARCHAR(36)` on a hot table [`uuid-database-primary-key.md`, `hibernate-and-uuid-identifiers.md`].
- Switching from `hilo` to `pooled` keeps existing ids intact but requires `setval(seq, MAX(id) + 1)` followed by `ALTER SEQUENCE ... INCREMENT BY allocationSize`; skipping either step yields the Hibernate `MappingException` about mismatched increment size at startup [`migrate-hilo-hibernate-pooled.md`].
- The `pooled-lo` variant uses the sequence value as the lower bound of the next pool (instead of the upper bound used by `pooled`); pick `pooled-lo` when external readers query `currval(seq)` and expect it to be the next id about to be inserted [`hibernate-batch-sequence-generator.md`, `migrate-hilo-hibernate-pooled.md`].

## Citations

- `change-one-to-one-primary-key-column-jpa-hibernate.md` -- `@MapsId` pattern for one-to-one PK sharing, replaces a separate FK column with a shared PK.
- `database-primary-key-flavors.md` -- Surrogate vs natural keys, UUID drawbacks on clustered indexes, per-engine sequence/identity matrix, IDENTITY disables JDBC batching.
- `from-jpa-to-hibernates-legacy-and-enhanced-identifier-generators.md` -- Map of legacy Hibernate generators (`uuid`, `seqhilo`) to the enhanced ones (`uuid2`, `SequenceStyleGenerator`).
- `hibernate-and-uuid-identifiers.md` -- `uuid2` vs `uuid` strategies, RFC 4122 variants, mapping `UUID` to `BINARY(16)`.
- `hibernate-batch-sequence-generator.md` -- Custom `BatchSequenceGenerator` to allocate many sequence values in a single round trip via `SELECT NEXTVAL` over `generate_series`.
- `hibernate-identity-sequence-and-table-sequence-generator.md` -- Side-by-side behavior of IDENTITY, SEQUENCE and TABLE generators, including the JDBC-batching restriction on IDENTITY.
- `hide-jpa-entity-identifier.md` -- Hiding the generated id behind an immutable accessor and avoiding public setters.
- `how-to-combine-the-hibernate-assigned-generator-with-a-sequence-or-an-identity-column.md` -- Custom `IdentifierGenerator` that respects a pre-assigned id and otherwise delegates to a sequence/identity.
- `how-to-emulate-createdby-and-lastmodifiedby-from-spring-data-using-the-generatortype-hibernate-annotation.md` -- `@GeneratorType` Hibernate annotation for value generation alongside id generation.
- `how-to-implement-a-custom-string-based-sequence-identifier-generator-with-hibernate.md` -- Custom `IdentifierGenerator` that produces formatted `String` ids (e.g., `ORD-0000001`) on top of a numeric sequence.
- `how-to-implement-equals-and-hashcode-using-the-jpa-entity-identifier.md` -- Constant `hashCode` + null-safe id `equals` is the only contract that survives all entity state transitions.
- `how-to-map-a-composite-identifier-using-an-automatically-generatedvalue-with-jpa-and-hibernate.md` -- Composite key where one column is generated and the other is assigned, using a custom generator.
- `how-to-map-a-manytoone-association-using-a-non-primary-key-column.md` -- `@JoinColumn(referencedColumnName=...)` to associate via a non-PK unique column.
- `how-to-replace-the-table-identifier-generator-with-either-sequence-or-identity-in-a-portable-way.md` -- Portable replacement of `GenerationType.TABLE` by switching strategy per dialect.
- `jpa-entity-identifier-sequence.md` -- `@SequenceGenerator` configuration, `allocationSize`, default `pooled` optimizer in Hibernate 5+.
- `migrate-hilo-hibernate-pooled.md` -- Step-by-step migration from legacy `hilo` to the `pooled` optimizer including the `setval` + `ALTER SEQUENCE INCREMENT BY` SQL.
- `replace-deprecated-genericgenerator.md` -- Replace deprecated `@GenericGenerator` mappings (`"uuid"`, `"sequence"`, `"seqhilo"`) with the JPA-standard or `"uuid2"` equivalents.
- `the-best-way-to-map-a-composite-primary-key-with-jpa-and-hibernate.md` -- `@EmbeddedId` with `Serializable` embeddable, `equals`/`hashCode` from columns, and `@ManyToOne` inside the embeddable.
- `the-best-way-to-map-a-naturalid-business-key-with-jpa-and-hibernate.md` -- Use a surrogate PK plus `@NaturalId` for the business key; `bySimpleNaturalId` and `@NaturalIdCache` for zero-SQL fetch.
- `the-hilo-algorithm.md` -- The hi/lo formula `[(hi-1)*incrementSize + 1, hi*incrementSize]` and why the sequence value is a bucket, not an id.
- `tsid-identifier-jpa-hibernate.md` -- `@Tsid` from Hypersistence Utils, 64-bit time-sorted ids on `Long`/`String`/`TSID`, custom `TSID.Factory` with node bits.
- `uuid-database-primary-key.md` -- Why random UUIDv4 is a poor PK; TSID is a 64-bit time-sorted alternative; UUIDv7 and SQL Server `NEWSEQUENTIALID` are 128-bit and less compact.
- `uuid-identifier-jpa-hibernate.md` -- `GenerationType.AUTO` on a `UUID` field, JVM- vs database-generated UUID via `UUIDGenerationStrategy`, batching works because the id is known before insert.
- `why-you-should-never-use-the-table-identifier-generator-with-jpa-and-hibernate.md` -- `GenerationType.TABLE` uses `SELECT FOR UPDATE` plus a separate transaction; serializes id generation and underperforms IDENTITY and SEQUENCE under concurrency.
