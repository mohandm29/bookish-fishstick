# Custom Type Mapping (JSON, Enum, Time, Arrays, Converters)

## Core Principles

- Prefer the JPA `AttributeConverter` for stateless one-to-one transformations between a Java type and a single JDBC column type. Use it when you do not need to customize JDBC binding/fetching, dirty checking, or multi-column mapping. The converter exposes `convertToDatabaseColumn` and `convertToEntityAttribute`, both of which must handle `null` defensively because JPA invokes them for every read and write. [jpa-attributeconverter.md]

- Use a Hibernate `UserType` (or `JdbcType`/`JavaType` SPI on Hibernate 6) when you must control how the value is bound to `PreparedStatement` and fetched from `ResultSet`, when you need a non-trivial `equals`/`hashCode`/`deepCopy`/`disassemble` contract for dirty tracking, or when the mapping spans multiple columns. `AttributeConverter` cannot provide that level of control. [how-to-implement-a-custom-basic-type-using-hibernate-usertype.md]

- Do not roll your own JSON type. Use `JsonType` (or the database-specific `JsonBinaryType`/`JsonStringType`/`JsonNodeBinaryType`) from `hypersistence-utils` (formerly `hibernate-types`). It already handles PostgreSQL `jsonb`, MySQL `json`, Oracle `JSON`/`CLOB`, and SQL Server `nvarchar(max)` correctly. [how-to-map-json-objects-using-generic-hibernate-types.md] [hibernate-types-hypersistence-utils.md]

- The legacy group id `com.vladmihalcea:hibernate-types-*` is superseded by `io.hypersistence:hypersistence-utils-hibernate-{55|60|62|63}`. Package prefix changed from `com.vladmihalcea.hibernate.type` to `io.hypersistence.utils.hibernate.type`. Migrate when bumping Hibernate. [hibernate-types-hypersistence-utils.md]

- For enums, `@Enumerated(EnumType.ORDINAL)` is dangerous: reordering or inserting a constant silently corrupts existing rows. Prefer `EnumType.STRING` for readability, the database-native enum type via `PostgreSQLEnumJdbcType` (Hibernate 6) / `PostgreSQLEnumType` (Hibernate 5) for compact storage, or a lookup table joined by a SMALLINT id when you need descriptions. [the-best-way-to-map-an-enum-type-with-jpa-and-hibernate.md]

- Use `java.time` types (`LocalDate`, `LocalDateTime`, `Instant`, `OffsetDateTime`) as mandated by JPA 2.2, not `java.util.Date`/`java.sql.Timestamp`/`Calendar`. The legacy `Date`/`Calendar` API is mutable, not thread-safe, and forces time-zone bugs at every JDBC boundary. [date-timestamp-jpa-hibernate.md] [how-to-store-date-time-and-timestamps-in-utc-time-zone-with-jdbc-and-hibernate.md]

- Always set `hibernate.jdbc.time_zone=UTC` (and run the database in UTC) so `PreparedStatement.setTimestamp` and `ResultSet.getTimestamp` use a UTC `Calendar` rather than the JVM default zone. Without it, every redeploy on a host with a different `TimeZone.getDefault()` shifts your data. [how-to-store-date-time-and-timestamps-in-utc-time-zone-with-jdbc-and-hibernate.md]

- For `OffsetDateTime`/`ZonedDateTime` where the offset matters semantically (e.g., audit trail of "when did the user see this"), use `@TimeZoneStorage(TimeZoneStorageType.COLUMN)` + `@TimeZoneColumn(name="...")` to persist the offset in a sibling column. By default Hibernate collapses the value to UTC and discards the offset. [offsetdatetime-zoneoffset-hibernate-timezonecolumn.md] [oracle-timestamp-with-time-zone-jpa.md]

- Map PostgreSQL arrays with `IntArrayType`/`StringArrayType`/`EnumArrayType` etc. from `hypersistence-utils`. Never store an array as a comma-joined `VARCHAR` — you lose `GIN`/`ANY()` indexing, type safety, and atomic element updates. For `hstore` use `PostgreSQLHStoreType`; for `interval`, `PostgreSQLIntervalType` maps to `java.time.Duration`. [hibernate-hsqldb-array-type.md] [multidimensional-array-jpa-hibernate.md] [map-postgresql-hstore-jpa-entity-property-hibernate.md] [map-postgresql-interval-java-duration-hibernate.md]

- Use `@Formula` for read-only derived columns evaluated by the database on every SELECT (it injects a SQL expression into the entity's SELECT list). Use `@ColumnTransformer(read=..., write=...)` for symmetric transformations like `pgp_sym_encrypt`/`pgp_sym_decrypt`. Use `@Generated(GenerationTime.INSERT|ALWAYS)` for columns whose values originate from DB defaults, triggers, or `GENERATED ALWAYS AS` expressions so Hibernate re-reads them after INSERT/UPDATE. [how-to-map-calculated-properties-with-jpa-and-hibernate-formula-annotation.md] [encrypt-decrypt-json-jpa.md]

- `java.util.Optional` is not `Serializable` and must not be used as a persistent field type. Keep the persistent field as the underlying nullable type with field-based access and expose `Optional.ofNullable(field)` only through the getter; otherwise HttpSession passivation, second-level cache serialization, and Stateful EJB passivation will all fail. [the-best-way-to-map-a-java-1-8-optional-entity-attribute-with-jpa-and-hibernate.md]

- Combine `@DynamicUpdate` with JSON-typed columns so Hibernate only issues UPDATE for the JSON column when that column actually changed; otherwise every entity flush rewrites the entire (potentially large) JSON payload, defeating row-level deduplication and inflating WAL. [hibernate-dynamic-update-json-properties.md]

## Decision Trees

### Mapping JSON?

```
Need to store a JSON document on a column?
├── Yes — use hypersistence-utils
│   ├── PostgreSQL `jsonb` column?
│   │   └── @Type(JsonBinaryType.class) (H5) / @Type(JsonType.class) (H6)
│   │       columnDefinition = "jsonb"
│   ├── PostgreSQL `json` (textual) column?
│   │   └── @Type(JsonType.class), columnDefinition = "json"
│   ├── MySQL `json` column?
│   │   └── @Type(JsonType.class), columnDefinition = "json"
│   ├── Oracle 21c+ native `JSON`?
│   │   └── @Type(JsonType.class), columnDefinition = "JSON"
│   ├── Oracle <21 (BLOB/CLOB JSON)?
│   │   └── @Type(JsonBlobType.class) or JsonStringType
│   └── SQL Server (no native JSON)?
│       └── @Type(JsonType.class), columnDefinition = "nvarchar(max)"
│           Add CHECK (ISJSON(col) = 1) constraint in DDL.
└── No — do not abuse JSON for normalized data.
    Prefer columns + FK so the optimizer can index and join.
```

Reference: [how-to-map-json-objects-using-generic-hibernate-types.md] [how-to-map-json-collections-using-jpa-and-hibernate.md] [java-map-json-jpa-hibernate.md] [oracle-json-jpa-hibernate.md] [mysql-json-table.md] [sql-server-json-hibernate.md] [json-property-value-postgresql.md].

### Mapping an enum?

```
Will the enum constants ever be reordered, renamed, or have constants inserted in the middle?
├── Possibly (typical) →
│   ├── Read-heavy, storage cost matters?
│   │   └── DB-native enum: @Enumerated(EnumType.STRING)
│   │       + @JdbcType(PostgreSQLEnumJdbcType.class)  (Hibernate 6)
│   │       + columnDefinition = "post_status"
│   │   OR lookup table joined by SMALLINT id with name + description
│   └── Default →
│       @Enumerated(EnumType.STRING) + @Column(length = N)
└── Never (closed taxonomy, 100M+ rows, every byte matters) →
    @Enumerated(EnumType.ORDINAL) + columnDefinition = "smallint"
    AND lock the enum order with explicit constant ids via converter.
```

Reference: [the-best-way-to-map-an-enum-type-with-jpa-and-hibernate.md].

### Mapping date/time?

```
What does the column represent?
├── A date with no time (birthday, due date)        → LocalDate         → DATE
├── A wall-clock time with no date (opening hours)  → LocalTime         → TIME
├── A wall-clock date+time, zone irrelevant         → LocalDateTime     → TIMESTAMP
├── A point in time, zone-agnostic (audit, event)   → Instant           → TIMESTAMP WITH TIME ZONE
│                                                                       (set hibernate.jdbc.time_zone=UTC)
├── A point in time + offset must be preserved      → OffsetDateTime
│     ├── PG/Oracle TIMESTAMPTZ available           → plain @Column (offset normalized to UTC)
│     └── Must preserve original offset             → @TimeZoneStorage(COLUMN) + @TimeZoneColumn
└── Year+month only (subscription renewal month)    → YearMonth         → AttributeConverter to Integer or DATE
    Month+day only (annual recurrence)              → MonthDay          → AttributeConverter to DATE
```

Reference: [date-timestamp-jpa-hibernate.md] [how-to-store-date-time-and-timestamps-in-utc-time-zone-with-jdbc-and-hibernate.md] [offsetdatetime-zoneoffset-hibernate-timezonecolumn.md] [oracle-timestamp-with-time-zone-jpa.md] [java-time-year-month-jpa-hibernate.md] [java-yearmonth-jpa-hibernate.md] [jpa-attributeconverter.md].

### Derived / DB-side / sensitive columns?

```
Where does the value live?
├── Computed from other columns of THIS row at SELECT time
│   → @Formula("expr")                         (read-only)
├── Stored in DB but transformed on read/write
│   ├── Encrypted at rest, transparent to app
│   │   → @ColumnTransformer(read="pgp_sym_decrypt(col, :key)",
│   │                       write="pgp_sym_encrypt(?, :key)")
│   └── Encrypted only specific JSON properties (search/index outside)
│       → AttributeConverter on the nested DTO with JsonType wrapper
├── Default / sequence / trigger / GENERATED ALWAYS AS
│   ├── On INSERT only            → @Generated(GenerationTime.INSERT)
│   └── On every INSERT and UPDATE → @Generated(GenerationTime.ALWAYS)
└── Computed in Java each call    → @Transient + getter
```

Reference: [how-to-map-calculated-properties-with-jpa-and-hibernate-formula-annotation.md] [encrypt-decrypt-json-jpa.md] [a-beginners-guide-to-hibernate-types.md].

### Custom basic type — AttributeConverter or UserType?

```
Does the mapping need any of these?
  - multi-column composite
  - custom equals / hashCode / dirty check semantics
  - access to the JDBC PreparedStatement / ResultSet
  - cache disassembly/assembly control
  - awareness of session / SharedSessionContractImplementor
├── No  → AttributeConverter<X, Y>          (portable JPA)
└── Yes → UserType<X> (Hibernate 6) or implement JavaType + JdbcType SPI
```

Reference: [jpa-attributeconverter.md] [how-to-implement-a-custom-basic-type-using-hibernate-usertype.md] [a-beginners-guide-to-hibernate-types.md] [monetaryamount-jpa-hibernate.md].

## Anti-patterns: WRONG / CORRECT

### 1. Using `@Enumerated(ORDINAL)` on a mutable enum

WRONG — inserting `REJECTED` between `PENDING` and `APPROVED` silently reassigns every existing `APPROVED` row to mean `REJECTED`:

```java
public enum PostStatus { PENDING, APPROVED, SPAM }

@Entity
public class Post {
    @Id Long id;

    @Enumerated // defaults to ORDINAL — stores 0,1,2 in the column
    private PostStatus status;
}
```

CORRECT — use STRING (small enums), DB-native enum (PostgreSQL), or a lookup table:

```java
@Entity
public class Post {
    @Id Long id;

    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private PostStatus status;
}

// or, PostgreSQL native enum (Hibernate 6):
@Enumerated(EnumType.STRING)
@JdbcType(PostgreSQLEnumJdbcType.class)
@Column(columnDefinition = "post_status_info")
private PostStatus status;
```

Source: [the-best-way-to-map-an-enum-type-with-jpa-and-hibernate.md].

### 2. `java.util.Date` instead of `java.time`

WRONG — `java.util.Date` is mutable, prints in the JVM default zone, and bypasses JPA 2.2 type support:

```java
@Entity
public class Book {
    @Id Long id;

    @Temporal(TemporalType.TIMESTAMP)
    private java.util.Date createdOn;  // legacy
}
```

CORRECT — use `Instant` for "moment in time" and `LocalDate`/`LocalDateTime` for wall-clock concepts:

```java
@Entity
public class Book {
    @Id Long id;

    @Column(name = "created_on")
    private Instant createdOn;          // point in time, UTC
}
```

Source: [date-timestamp-jpa-hibernate.md] [how-to-store-date-time-and-timestamps-in-utc-time-zone-with-jdbc-and-hibernate.md].

### 3. Forgetting `hibernate.jdbc.time_zone`

WRONG — relying on whatever `TimeZone.getDefault()` returns on the box, so the same code writes different rows on different hosts:

```properties
# application.properties — no time_zone set
spring.jpa.properties.hibernate.show_sql=true
```

```java
book.setCreatedOn(Timestamp.from(
    ZonedDateTime.of(2016, 8, 25, 11, 23, 46, 0,
                     ZoneId.of("UTC")).toInstant()));
// On a JVM in US/Hawaii this writes '2016-08-25 01:23:46' to PG
```

CORRECT — pin the JDBC time zone and the DB session zone to UTC:

```properties
spring.jpa.properties.hibernate.jdbc.time_zone=UTC
# Also: ALTER DATABASE ... SET timezone TO 'UTC'; (PostgreSQL)
```

Source: [how-to-store-date-time-and-timestamps-in-utc-time-zone-with-jdbc-and-hibernate.md].

### 4. Manually stringifying JSON into a `String` column

WRONG — hand-rolled JSON-as-VARCHAR loses jsonb operators, indexing, and round-trip type fidelity:

```java
@Entity
public class Event {
    @Id Long id;

    @Column(columnDefinition = "text")
    private String locationJson;        // serialized by hand with ObjectMapper

    @Transient
    public Location getLocation() throws IOException {
        return new ObjectMapper().readValue(locationJson, Location.class);
    }
}
```

CORRECT — use `JsonType` from hypersistence-utils so Hibernate binds the value as the proper JDBC type for `jsonb`/`json`:

```java
@Entity
public class Event {
    @Id Long id;

    @Type(JsonType.class)               // Hibernate 6
    @Column(columnDefinition = "jsonb")
    private Location location;
}
```

Source: [how-to-map-json-objects-using-generic-hibernate-types.md] [hibernate-types-hypersistence-utils.md].

### 5. `AttributeConverter` that drops null-safety

WRONG — NPE on read whenever a column is NULL, and silent default-write on write:

```java
public class MonthDayDateAttributeConverter
        implements AttributeConverter<MonthDay, java.sql.Date> {

    @Override
    public java.sql.Date convertToDatabaseColumn(MonthDay monthDay) {
        return java.sql.Date.valueOf(monthDay.atYear(1)); // NPE if null
    }

    @Override
    public MonthDay convertToEntityAttribute(java.sql.Date date) {
        LocalDate ld = date.toLocalDate();               // NPE if null
        return MonthDay.of(ld.getMonth(), ld.getDayOfMonth());
    }
}
```

CORRECT — guard both directions; JPA does NOT skip the converter for null:

```java
@Converter(autoApply = true)
public class MonthDayDateAttributeConverter
        implements AttributeConverter<MonthDay, java.sql.Date> {

    @Override
    public java.sql.Date convertToDatabaseColumn(MonthDay monthDay) {
        if (monthDay == null) return null;
        return java.sql.Date.valueOf(monthDay.atYear(1));
    }

    @Override
    public MonthDay convertToEntityAttribute(java.sql.Date date) {
        if (date == null) return null;
        LocalDate ld = date.toLocalDate();
        return MonthDay.of(ld.getMonth(), ld.getDayOfMonth());
    }
}
```

Source: [jpa-attributeconverter.md].

### 6. Mapping `OffsetDateTime` as plain `TIMESTAMP` when offset matters

WRONG — Hibernate normalizes to UTC and discards the original offset; you cannot reconstruct "the user clicked at 12:30 local +12:00":

```java
@Entity
public class Post {
    @Id Long id;

    @Column(name = "published_on")
    private OffsetDateTime publishedOn; // offset is lost on read
}
```

CORRECT — persist the offset in a sibling column:

```java
@Entity
public class Post {
    @Id Long id;

    @Column(name = "published_on")
    @TimeZoneStorage(TimeZoneStorageType.COLUMN)
    @TimeZoneColumn(name = "published_on_offset")
    private OffsetDateTime publishedOn;
}
```

Source: [offsetdatetime-zoneoffset-hibernate-timezonecolumn.md] [oracle-timestamp-with-time-zone-jpa.md].

### 7. `@Lob` with mixed payload types

WRONG — using `@Lob` on a `String` triggers `oid`/`clob` behavior on PostgreSQL (large-object table indirection), breaking simple text queries:

```java
@Entity
public class Article {
    @Id Long id;

    @Lob
    private String body;   // becomes OID on PG — needs lo_get / pg_largeobject
}
```

CORRECT — declare the exact column type you want:

```java
@Entity
public class Article {
    @Id Long id;

    @Column(columnDefinition = "text")  // PG TEXT, no OID indirection
    private String body;
}
```

Source: [a-beginners-guide-to-hibernate-types.md].

### 8. Storing PostgreSQL arrays as VARCHAR

WRONG — comma-joined string defeats `GIN` indexing, `ANY(?)` predicates, and atomic element updates:

```java
@Entity
public class Sensor {
    @Id Long id;

    @Column(columnDefinition = "varchar(2000)")
    private String tags;   // "alpha,beta,gamma"
}
```

CORRECT — use the native array type via hypersistence-utils:

```java
@Entity
@TypeDef(name = "string-array", typeClass = StringArrayType.class) // H5
public class Sensor {
    @Id Long id;

    @Type(StringArrayType.class)                                   // H6
    @Column(columnDefinition = "text[]")
    private String[] tags;
}
```

Source: [hibernate-hsqldb-array-type.md] [multidimensional-array-jpa-hibernate.md].

### 9. Mapping `Optional` as a persistent field

WRONG — `Optional` is not `Serializable`; HttpSession replication, EJB passivation, and 2L cache disassembly all fail:

```java
@Entity
public class PostComment implements Serializable {
    @Id Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Optional<Attachment> attachment;   // not Serializable
}
```

CORRECT — keep field as the nullable persistent type; expose `Optional` only through the getter on field-access entities:

```java
@Entity
public class PostComment implements Serializable {
    @Id Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Attachment attachment;

    public Optional<Attachment> getAttachment() {
        return Optional.ofNullable(attachment);
    }
}
```

Source: [the-best-way-to-map-a-java-1-8-optional-entity-attribute-with-jpa-and-hibernate.md].

### 10. Computing derived value in Java when SQL can do it

WRONG — every read goes through Java; you cannot filter/sort by the derived value in JPQL or in the database:

```java
@Entity
public class Account {
    @Id Long id;
    private long cents;

    @Transient
    public double getDollars() { return cents / 100D; }
}
```

CORRECT — `@Formula` projects the SQL expression into the entity's SELECT, so it is queryable:

```java
@Entity
public class Account {
    @Id Long id;
    private long cents;

    @Formula("cents / 100.0")
    private double dollars;
}
```

Source: [how-to-map-calculated-properties-with-jpa-and-hibernate-formula-annotation.md].

### 11. Hand-encrypting in service code instead of `@ColumnTransformer`

WRONG — every caller has to remember to encrypt/decrypt and JPQL `where`-by-cipher-text breaks:

```java
user.setSsnEncrypted(cipher.encrypt(plainSsn));
em.persist(user);
// ... later
String ssn = cipher.decrypt(user.getSsnEncrypted());
```

CORRECT — push the symmetric transformation into SQL so the entity attribute stays the plaintext type:

```java
@Entity
public class User {
    @Id Long id;

    @ColumnTransformer(
        read  = "pgp_sym_decrypt(ssn, current_setting('app.key'))",
        write = "pgp_sym_encrypt(?,  current_setting('app.key'))"
    )
    private String ssn;
}
```

Source: [encrypt-decrypt-json-jpa.md].

## Performance Pitfalls

- Large JSON payloads on a `@DynamicUpdate`-less entity cause Hibernate to rewrite the JSON column on every flush even when only a scalar changed. Add `@DynamicUpdate` to JSON-bearing entities so the UPDATE only touches modified columns; the database avoids re-toasting/recompressing untouched `jsonb` blobs. [hibernate-dynamic-update-json-properties.md]

- Filtering or projecting from inside `jsonb` without a `GIN` index forces a full sequential scan: `CREATE INDEX ON event USING GIN (location jsonb_path_ops);` for `@>` containment, and an expression index `CREATE INDEX ON event ((location->>'city'))` for scalar lookups. The Java mapping cannot help if the DB cannot index. [index-json-columns-mysql.md] [json-property-value-postgresql.md].

- `AttributeConverter` is invoked on every read for every row, including JPQL result mapping. For high-cardinality reads, prefer a Hibernate `JavaType`/`UserType` that can short-circuit using `==` identity for immutable values, and make sure `equals`/`hashCode` are cheap to avoid pathological dirty checks. [how-to-implement-a-custom-basic-type-using-hibernate-usertype.md]

- Autoboxing on enum ORDINAL/Integer converters allocates an `Integer` per row. For wide result sets (millions of rows in batch jobs), favor primitive-typed columns plus a converter that returns cached, interned instances (e.g., `EnumSet`-backed lookup) rather than boxing on every fetch. [the-best-way-to-map-an-enum-type-with-jpa-and-hibernate.md]

- Storing wide JSON documents (kilobytes) inflates buffer pool footprint and TOAST overhead. Split out search/filter scalars into their own columns (event_date, status) and keep JSON for opaque payloads. Use the JSON column for what cannot be modeled relationally, not for the entire aggregate. [how-to-store-schema-less-eav-entity-attribute-value-data-using-json-and-hibernate.md]

- `@Formula` re-evaluates the SQL expression on every SELECT and prevents the entity from being included in a `select count(*)` optimization when the formula references a subquery. Keep formulas to inline expressions over the same row's columns; push aggregates into a database view + `@Subselect` entity. [how-to-map-calculated-properties-with-jpa-and-hibernate-formula-annotation.md]

- Mapping `BigDecimal` money without `precision`/`scale` defaults to platform-specific behavior (often `DECIMAL(19,2)` or even `NUMERIC` without scale on PostgreSQL). Always declare `@Column(precision = 19, scale = 4)` for money. Consider `MonetaryAmount` with a composite `UserType` so currency travels with the value. [monetaryamount-jpa-hibernate.md]

- Custom Jackson `ObjectMapper` (e.g., to enable `JavaTimeModule` or snake_case) must be registered with `JsonType` via the `hibernate.types.jackson.object.mapper` property or by subclassing; otherwise every entity instance round-trips through Hypersistence Utils' default mapper, which may not match your REST layer's serialization, causing silent schema drift between API and DB. [hibernate-types-customize-jackson-objectmapper.md] [how-to-customize-the-json-serializer-used-by-hibernate-types.md]

- Mapping a Java record to a JSON column with `JsonType` works in Hibernate 6 only if the record is concrete and registered with Jackson's parameter-name module; otherwise deserialization throws at fetch time, not at boot, so the failure surfaces in production reads. [java-records-json-hibernate.md] [java-records-jpa-hibernate.md]

- `PostgreSQLIntervalType` returns `java.time.Duration`, which cannot represent months/years (variable length). Storing "1 month" as `Duration` is lossy; use `Period` + a custom converter, or split into `(months int, days int, seconds bigint)` columns when you need calendar arithmetic. [map-postgresql-interval-java-duration-hibernate.md]

## Citations

- [a-beginners-guide-to-hibernate-types.md] — Overview of Hibernate's built-in type system and when to extend it.
- [date-timestamp-jpa-hibernate.md] — Mapping `java.time` types to `DATE`/`TIME`/`TIMESTAMP` columns under JPA 2.2.
- [encrypt-decrypt-json-jpa.md] — `@ColumnTransformer` and JSON-property-level encryption patterns.
- [hibernate-dynamic-update-json-properties.md] — Why `@DynamicUpdate` is required to avoid rewriting large JSON columns on every flush.
- [hibernate-hsqldb-array-type.md] — Mapping SQL arrays via hypersistence-utils for HSQLDB / PostgreSQL.
- [hibernate-types-customize-jackson-objectmapper.md] — Customizing the Jackson `ObjectMapper` used by `JsonType`.
- [hibernate-types-hypersistence-utils.md] — Migration from `hibernate-types` to `hypersistence-utils` (groupId + package rename).
- [how-to-customize-the-json-serializer-used-by-hibernate-types.md] — Plugging a custom JSON serializer into Hypersistence Utils.
- [how-to-implement-a-custom-basic-type-using-hibernate-usertype.md] — Implementing `UserType` for control over JDBC bind/fetch and dirty checking.
- [how-to-map-calculated-properties-with-jpa-and-hibernate-formula-annotation.md] — `@Formula` for read-only derived columns.
- [how-to-map-json-collections-using-jpa-and-hibernate.md] — Mapping `List`/`Map` payloads onto JSON columns.
- [how-to-map-json-objects-using-generic-hibernate-types.md] — `JsonType` usage across MySQL, PostgreSQL, Oracle, SQL Server.
- [how-to-store-date-time-and-timestamps-in-utc-time-zone-with-jdbc-and-hibernate.md] — Setting `hibernate.jdbc.time_zone=UTC` to avoid JVM-zone drift.
- [how-to-store-schema-less-eav-entity-attribute-value-data-using-json-and-hibernate.md] — EAV-style schema-less data via JSON columns and trade-offs.
- [index-json-columns-mysql.md] — Indexing JSON paths in MySQL (functional/virtual columns).
- [java-map-json-jpa-hibernate.md] — Mapping `Map<String, ?>` onto JSON columns.
- [java-records-jpa-hibernate.md] — Records as JPA entities (Hibernate 6+) caveats.
- [java-records-json-hibernate.md] — Records inside JSON columns with `JsonType`.
- [java-time-year-month-jpa-hibernate.md] — Mapping `YearMonth` via converter.
- [java-yearmonth-jpa-hibernate.md] — Companion patterns and DB column shape for `YearMonth`.
- [jpa-attributeconverter.md] — `AttributeConverter<X, Y>` mechanics, `@Convert`, `@Converter(autoApply=true)`.
- [json-property-value-postgresql.md] — Querying inside `jsonb` from JPQL/native SQL on PostgreSQL.
- [map-postgresql-hstore-jpa-entity-property-hibernate.md] — PostgreSQL `hstore` to `Map<String,String>` via `PostgreSQLHStoreType`.
- [map-postgresql-interval-java-duration-hibernate.md] — PostgreSQL `interval` to `java.time.Duration` via `PostgreSQLIntervalType`.
- [map-string-jpa-property-json-column-hibernate.md] — Mapping a raw `String` field to a JSON DB column.
- [monetaryamount-jpa-hibernate.md] — `MonetaryAmount` (JSR-354) as a composite/custom type for money.
- [multidimensional-array-jpa-hibernate.md] — N-dimensional PostgreSQL arrays via hypersistence-utils.
- [mysql-json-table.md] — Indexing/querying MySQL `JSON` columns and the `JSON_TABLE` function.
- [offsetdatetime-zoneoffset-hibernate-timezonecolumn.md] — `@TimeZoneStorage(COLUMN)` + `@TimeZoneColumn` for offset preservation.
- [oracle-json-jpa-hibernate.md] — Oracle's native `JSON` column type with Hibernate.
- [oracle-timestamp-with-time-zone-jpa.md] — Oracle `TIMESTAMP WITH TIME ZONE` mapping nuances.
- [sql-server-json-hibernate.md] — Storing JSON as `nvarchar(max)` on SQL Server with `JsonType`.
- [the-best-way-to-map-a-java-1-8-optional-entity-attribute-with-jpa-and-hibernate.md] — Why `Optional` must not be a persistent field; expose via getter.
- [the-best-way-to-map-an-enum-type-with-jpa-and-hibernate.md] — Enum mapping trade-offs: ORDINAL vs STRING vs native enum vs lookup table.
- [the-hibernate-types-open-source-project-is-born.md] — Origin of the Hibernate Types / Hypersistence Utils project.
