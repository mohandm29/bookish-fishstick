# Entity Inheritance Mapping

## Core Principles

- Use entity inheritance to vary behavior (Strategy, Visitor), not to share columns; prefer composition or `@MappedSuperclass` when only reusing data structure [the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md].
- `SINGLE_TABLE` is the JPA default and is the most efficient inheritance strategy for reads and writes: one table, no joins for polymorphic queries, single join for parent/child associations [the-best-way-to-map-the-single_table-inheritance-with-jpa-and-hibernate.md].
- `SINGLE_TABLE` cannot enforce `NOT NULL` on subclass-specific columns at the column level; every subclass column must be nullable in DDL and constraints must move to SQL `CHECK` (Oracle, PostgreSQL, SQL Server, MySQL 8.0.16+) or `TRIGGER` (older MySQL) [the-best-way-to-map-the-single_table-inheritance-with-jpa-and-hibernate.md].
- `JOINED` inheritance gives normalized tables (subclass PK is also FK to base PK) and supports column-level `NOT NULL` on subclass attributes, but every polymorphic fetch costs a `LEFT OUTER JOIN` per subclass and emits a synthetic `clazz_` CASE column [the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md].
- `TABLE_PER_CLASS` (not covered explicitly in this cluster — refer to the same Vlad Mihalcea overview) forces `UNION ALL` for polymorphic queries on the base class, which scales poorly and prevents `IDENTITY` id generation; avoid unless polymorphic queries against the base class are never issued [the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md].
- `@MappedSuperclass` is NOT entity inheritance: the hierarchy exists only in Java; the base class is not queryable, has no table, and properties are simply copied into each subclass table [how-to-inherit-properties-from-a-base-class-entity-using-mappedsuperclass-with-jpa-and-hibernate.md] [the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md].
- Use `@MappedSuperclass` to reuse `@Id`, `@Version`, audit columns across entities without paying the polymorphic-query cost or coupling tables [how-to-inherit-properties-from-a-base-class-entity-using-mappedsuperclass-with-jpa-and-hibernate.md].
- The default `@DiscriminatorColumn` is `DTYPE VARCHAR` storing the entity class simple name; this column is implicitly added to every WHERE clause of subclass JPQL queries and is therefore a prime index candidate [the-best-way-to-map-the-discriminatorcolumn-with-jpa-and-hibernate.md].
- Prefer `DiscriminatorType.INTEGER` with explicit `@DiscriminatorValue("1")` per subclass and `columnDefinition = "TINYINT(1)"`: a `TINYINT` index on 100M rows costs ~96 MB versus ~393 MB for the default `VARCHAR` discriminator [the-best-way-to-map-the-discriminatorcolumn-with-jpa-and-hibernate.md].
- Restore the lost descriptiveness of an integer discriminator by adding a small lookup table (`topic_type(id, name, description)`) joined only for reporting; runtime persistence still uses the integer code [the-best-way-to-map-the-discriminatorcolumn-with-jpa-and-hibernate.md].
- Hibernate 6.6+ supports `@Embeddable` inheritance: annotate the base embeddable with `@DiscriminatorColumn` and each subclass embeddable with `@DiscriminatorValue`, then map them inside an `@ElementCollection` of the parent entity [embeddable-inheritance-jpa-hibernate.md].
- For polymorphic data stored inside a JSON column, JPA `@Inheritance` does not apply; encode the type discriminator in the JSON payload via Jackson `@JsonTypeInfo`/`@JsonSubTypes` or `ObjectMapper.activateDefaultTypingAsProperty(...)` and persist with `JsonType` from Hypersistence Utils [polymorphic-json-objects-hibernate.md].

## Decision Trees

### 1. Choose an inheritance strategy
- Do subclasses ever need to be queried polymorphically through the base type?
  - No → use `@MappedSuperclass` (no shared table, no discriminator, no polymorphic SQL) [how-to-inherit-properties-from-a-base-class-entity-using-mappedsuperclass-with-jpa-and-hibernate.md].
  - Yes → continue.
- Is read/write throughput the dominant requirement and can subclass columns tolerate being nullable in DDL?
  - Yes → `SINGLE_TABLE` (default); enforce nullability via `CHECK`/`TRIGGER` keyed on the discriminator [the-best-way-to-map-the-single_table-inheritance-with-jpa-and-hibernate.md].
  - No, data-integrity via column-level `NOT NULL` is mandatory and you accept an extra join per polymorphic fetch → `JOINED` [the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md].
- Are polymorphic queries against the base class effectively never issued and you want fully independent tables? Consider `TABLE_PER_CLASS`, accepting `UNION ALL` cost when they do occur [the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md].

### 2. Need polymorphism?
- Need polymorphic JPQL/Criteria query on the base type? → `@Inheritance(strategy = ...)` (entity inheritance) [the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md].
- Only need to share `@Id`/`@Version`/audit fields across otherwise unrelated entities? → `@MappedSuperclass` (no polymorphic queries possible against the base class) [how-to-inherit-properties-from-a-base-class-entity-using-mappedsuperclass-with-jpa-and-hibernate.md].
- Need polymorphism inside an embedded value type collection? → `@Embeddable` inheritance with `@DiscriminatorColumn` (Hibernate 6.6+) [embeddable-inheritance-jpa-hibernate.md].
- Need polymorphism inside a JSON column? → Jackson `@JsonTypeInfo` + `JsonType`, not JPA `@Inheritance` [polymorphic-json-objects-hibernate.md].

### 3. Discriminator value selection
- Default (`STRING`, class name): acceptable when subclass count is tiny, names are short, and the column is rarely indexed [the-best-way-to-map-the-discriminatorcolumn-with-jpa-and-hibernate.md].
- High-row table with frequent subclass filtering → `DiscriminatorType.INTEGER` + `TINYINT` column + explicit `@DiscriminatorValue("N")` per subclass [the-best-way-to-map-the-discriminatorcolumn-with-jpa-and-hibernate.md].
- Need human-readable values without bloating the discriminator column → integer discriminator + sibling lookup table joined only for display [the-best-way-to-map-the-discriminatorcolumn-with-jpa-and-hibernate.md].

## Anti-patterns: WRONG / CORRECT

### 1. Relying on default `DTYPE VARCHAR` for a heavily-filtered, heavily-indexed hierarchy
WRONG [the-best-way-to-map-the-discriminatorcolumn-with-jpa-and-hibernate.md]:
```java
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
public class Topic { @Id @GeneratedValue private Long id; }

@Entity
public class Announcement extends Topic { } // stored as DTYPE='Announcement' (12 bytes)
```
CORRECT:
```java
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(
    discriminatorType = DiscriminatorType.INTEGER,
    name = "topic_type_id",
    columnDefinition = "TINYINT(1)"
)
public class Topic { @Id @GeneratedValue private Long id; }

@Entity
@DiscriminatorValue("1")
public class Post extends Topic { }

@Entity
@DiscriminatorValue("2")
public class Announcement extends Topic { }
```

### 2. Non-nullable subclass column in `SINGLE_TABLE`
WRONG [the-best-way-to-map-the-single_table-inheritance-with-jpa-and-hibernate.md]:
```java
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
public class Topic { @Id @GeneratedValue private Long id; }

@Entity
public class Post extends Topic {
    @Column(nullable = false) // breaks: column must accept NULL for Announcement rows
    private String content;
}
```
CORRECT — keep DDL nullable, enforce per-subclass non-null via `CHECK` (or trigger on MySQL < 8.0.16):
```java
@Entity
public class Post extends Topic {
    @Column // nullable at DDL level; CHECK enforces per-DTYPE
    private String content;
}
```
```sql
ALTER TABLE topic ADD CONSTRAINT post_content_check CHECK (
  CASE WHEN DTYPE = 'Post'
       THEN CASE WHEN content IS NOT NULL THEN 1 ELSE 0 END
       ELSE 1 END = 1);
```

### 3. Declaring an entity hierarchy without an `@Inheritance` strategy when you need JOINED tables
WRONG [the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md] — omitting `@Inheritance` silently selects `SINGLE_TABLE`, collapsing the intended per-subclass tables into one:
```java
@Entity
@Table(name = "notification")
public class Notification { @Id @GeneratedValue private Long id; }

@Entity
@Table(name = "sms_notification")
public class SmsNotification extends Notification { /* table is ignored */ }
```
CORRECT:
```java
@Entity
@Table(name = "notification")
@Inheritance(strategy = InheritanceType.JOINED)
public class Notification { @Id @GeneratedValue private Long id; }

@Entity
@Table(name = "sms_notification")
public class SmsNotification extends Notification {
    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;
}
```

### 4. Misusing `@MappedSuperclass` for a query-able hierarchy
WRONG [how-to-inherit-properties-from-a-base-class-entity-using-mappedsuperclass-with-jpa-and-hibernate.md] [the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md] — code expects polymorphic JPQL but base is not an entity:
```java
@MappedSuperclass
public class Notification { @Id @GeneratedValue private Long id; }

@Entity public class SmsNotification extends Notification { }
@Entity public class EmailNotification extends Notification { }

// Fails: Notification is not an entity, cannot appear in JPQL
List<Notification> all = em.createQuery(
    "select n from Notification n", Notification.class).getResultList();
```
CORRECT — switch to entity inheritance when you need polymorphic queries:
```java
@Entity
@Inheritance(strategy = InheritanceType.JOINED) // or SINGLE_TABLE
public class Notification { @Id @GeneratedValue private Long id; }
```

### 5. Using `@MappedSuperclass` correctly for shared id/version/audit
CORRECT [how-to-inherit-properties-from-a-base-class-entity-using-mappedsuperclass-with-jpa-and-hibernate.md]:
```java
@MappedSuperclass
public class BaseEntity {
    @Id @GeneratedValue
    private Long id;
    @Version
    private Integer version;
}

@Entity @Table(name = "post")
public class Post extends BaseEntity {
    private String title;
}

@Entity @Table(name = "tag")
public class Tag extends BaseEntity {
    @NaturalId
    private String name;
}
```

### 6. `TABLE_PER_CLASS` with frequent polymorphic queries
WRONG [the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md] — every `select n from Notification n` becomes a `UNION ALL` across all subclass tables; cost grows linearly with subclass count and prevents `IDENTITY` ids:
```java
@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
public class Notification {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) // forbidden
    private Long id;
}
```
CORRECT — use `SINGLE_TABLE` (default) or `JOINED` when polymorphic queries are needed:
```java
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
public class Notification {
    @Id @GeneratedValue
    private Long id;
}
```

### 7. Polymorphic JSON list without type information
WRONG [polymorphic-json-objects-hibernate.md] — abstract base cannot be instantiated by Jackson on read:
```java
public abstract class DiscountCoupon { private String name; }
public class AmountDiscountCoupon extends DiscountCoupon { private BigDecimal amount; }

@Entity
public class Book {
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private List<DiscountCoupon> coupons = new ArrayList<>(); // load fails
}
```
CORRECT — declare the discriminator in Jackson metadata:
```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(name = "discount.coupon.amount", value = AmountDiscountCoupon.class),
    @JsonSubTypes.Type(name = "discount.coupon.percentage", value = PercentageDiscountCoupon.class)
})
public abstract class DiscountCoupon { private String name; }

@Entity
public class Book {
    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private List<DiscountCoupon> coupons = new ArrayList<>();
}
```

### 8. Forgetting `equals`/`hashCode` on JSON polymorphic value objects
WRONG [polymorphic-json-objects-hibernate.md] — Hibernate dirty checking cannot detect changes inside the JSON list:
```java
public abstract class DiscountCoupon { private String name; /* no equals/hashCode */ }
```
CORRECT — provide value-based equality so dirty checking emits the UPDATE:
```java
@Override public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DiscountCoupon)) return false;
    return Objects.equals(getName(), ((DiscountCoupon) o).getName());
}
@Override public int hashCode() { return Objects.hash(getName()); }
```

### 9. Embeddable hierarchy without discriminator metadata (Hibernate 6.6+)
WRONG [embeddable-inheritance-jpa-hibernate.md] — Hibernate cannot rehydrate the correct subclass:
```java
@Embeddable
public class Subscription { private boolean optIn; }

@Embeddable
public class EmailSubscription extends Subscription { private String emailAddress; }
```
CORRECT:
```java
@Embeddable
@DiscriminatorColumn(name = "subscription_type")
public class Subscription<T extends Subscription<T>> {
    @Column(name = "opt_in") private boolean optIn;
}

@Embeddable
@DiscriminatorValue("email")
public class EmailSubscription extends Subscription<EmailSubscription> {
    @Column(name = "email_address") private String emailAddress;
}

@Embeddable
@DiscriminatorValue("sms")
public class SmsSubscription extends Subscription<SmsSubscription> {
    @Column(name = "phone_number") private Long phoneNumber;
}

@Entity
public class Subscriber {
    @Id private Long id;
    @ElementCollection
    @CollectionTable(name = "subscriptions",
        joinColumns = @JoinColumn(name = "parent_id"))
    private Set<Subscription> subscriptions = new HashSet<>();
}
```

### 10. Auto-wired Strategy registry typed by `Class<? extends Base>` requires entity inheritance
WRONG — using `@MappedSuperclass` with this pattern breaks because `notification.getClass()` returns concrete classes but `Notification` is not an entity, so `notificationDAO.findAll()` on the base type is impossible [the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md]:
```java
@MappedSuperclass public class Notification { }
List<Notification> all = notificationDAO.findAll(); // cannot be implemented polymorphically
```
CORRECT — entity inheritance enables the polymorphic Criteria query feeding the Strategy map:
```java
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
public class Notification { @Id @GeneratedValue private Long id; }

CriteriaQuery<Notification> cq = cb.createQuery(Notification.class);
cq.from(Notification.class);
List<Notification> all = em.createQuery(cq).getResultList();
for (Notification n : all) notificationSenderMap.get(n.getClass()).send(n);
```

## Performance Pitfalls

- `SINGLE_TABLE` indexes on the discriminator column dominate the index size: switching `DTYPE VARCHAR` to a `TINYINT` discriminator on 100M rows drops index storage from ~393 MB to ~96 MB [the-best-way-to-map-the-discriminatorcolumn-with-jpa-and-hibernate.md].
- Every JPQL query against a subclass implicitly adds `WHERE DTYPE = '<subclass>'`; if that column is not selective or not indexed, queries scan the whole hierarchy table [the-best-way-to-map-the-discriminatorcolumn-with-jpa-and-hibernate.md].
- `JOINED` polymorphic SELECTs emit one `LEFT OUTER JOIN` per subclass plus a synthetic `CASE WHEN ... clazz_` projection; cost grows with the number of subclasses even when only one is needed [the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md].
- `TABLE_PER_CLASS` polymorphic queries are `UNION ALL` across every subclass table — pagination, sorting, and locking all degrade as you add subclasses [the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md].
- `@MappedSuperclass` cannot back a polymorphic Strategy dispatcher pattern, so reaching for it to "save a join" forces application-side fan-out queries that are slower than `JOINED` [the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md] [how-to-inherit-properties-from-a-base-class-entity-using-mappedsuperclass-with-jpa-and-hibernate.md].
- Defaulting `@OneToOne`/`@ManyToOne` to eager fetch on a base-class entity multiplies the join cost across the whole hierarchy on every load; declare `FetchType.LAZY` on `Topic.board` and similar associations [how-to-inherit-properties-from-a-base-class-entity-using-mappedsuperclass-with-jpa-and-hibernate.md].
- For JSON polymorphism, omitting `equals`/`hashCode` on the polymorphic value type defeats Hibernate dirty checking, so in-place mutations of list elements never produce an UPDATE [polymorphic-json-objects-hibernate.md].
- `@ElementCollection` of embeddable hierarchies stores all subclass columns side-by-side in the collection table; just like `SINGLE_TABLE`, subclass columns must be nullable and `NOT NULL` integrity must move to `CHECK` constraints keyed on the embeddable discriminator [embeddable-inheritance-jpa-hibernate.md] [the-best-way-to-map-the-single_table-inheritance-with-jpa-and-hibernate.md].
- MySQL prior to 8.0.16 ignores `CHECK` clauses; if you depend on subclass non-nullability there, replace `CHECK` with explicit `BEFORE INSERT`/`BEFORE UPDATE` triggers per (DTYPE, column) pair [the-best-way-to-map-the-single_table-inheritance-with-jpa-and-hibernate.md].
- Reading `getCoupons()` after persisting via `DefaultTyping.OBJECT_AND_NON_CONCRETE` adds a `type` property to every JSON document; if downstream consumers parse the JSON without that field they will reject the payload [polymorphic-json-objects-hibernate.md].

## Citations

- `[embeddable-inheritance-jpa-hibernate.md]` — Hibernate 6.6+ `@Embeddable` inheritance with `@DiscriminatorColumn`/`@DiscriminatorValue` inside `@ElementCollection`.
- `[how-to-inherit-properties-from-a-base-class-entity-using-mappedsuperclass-with-jpa-and-hibernate.md]` — `@MappedSuperclass` for reusing `@Id`/`@Version`/audit fields without entity inheritance.
- `[polymorphic-json-objects-hibernate.md]` — Polymorphic JSON columns via Jackson `@JsonTypeInfo`/`@JsonSubTypes` and Hypersistence Utils `JsonType`.
- `[the-best-way-to-map-the-discriminatorcolumn-with-jpa-and-hibernate.md]` — Replacing the default `VARCHAR DTYPE` with a `TINYINT` `INTEGER` discriminator plus optional lookup table.
- `[the-best-way-to-map-the-single_table-inheritance-with-jpa-and-hibernate.md]` — `SINGLE_TABLE` strategy, nullability limits, and `CHECK`/`TRIGGER` workarounds.
- `[the-best-way-to-use-entity-inheritance-with-jpa-and-hibernate.md]` — When to use entity inheritance (Strategy/Visitor), `JOINED` mapping, `@MappedSuperclass` vs `@Inheritance`.
