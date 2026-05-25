# Audit Logs and Change Data Capture (CDC)

## Core Principles

- Pick exactly one source of truth for change events. Mixing app-layer auditing (Envers, `@EntityListeners`) with DB-layer triggers/CDC produces duplicate, inconsistent rows because each layer sees different transaction boundaries [the-best-way-to-implement-an-audit-log-using-hibernate-envers.md].
- Hibernate Envers captures changes inside the same `EntityTransaction` via Hibernate event listeners (`@Audited` triggers `RevisionInfo` + `_AUD` row inserts during flush). This guarantees the audit row is atomic with the business change but only captures changes that flow through Hibernate — JDBC batch jobs, SQL consoles, replication, and other apps bypass it entirely [the-best-way-to-implement-an-audit-log-using-hibernate-envers.md, a-beginners-guide-to-cdc-change-data-capture.md].
- Database triggers (`AFTER INSERT/UPDATE/DELETE`) capture every DML regardless of origin and are the right choice when the DB is shared by multiple apps or accepts direct SQL. The trade-off is per-statement synchronous overhead and dialect-specific PL/pgSQL / T-SQL code [postgresql-audit-logging-triggers.md, mysql-audit-logging-triggers.md, sql-server-audit-logging-triggers.md].
- Transaction-log-based CDC (Debezium reading MySQL binlog, Postgres WAL, SQL Server CDC, Oracle redo) is the most efficient option because the log is written anyway: CDC adds no write amplification to OLTP transactions and decouples consumers via Kafka [a-beginners-guide-to-cdc-change-data-capture.md, how-to-extract-change-data-events-from-mysql-to-kafka-using-debezium.md].
- The audit log table is conceptually a duplicate of the redo/WAL — if you only need downstream consumers (cache invalidation, search index, data warehouse), prefer log-based CDC over writing a parallel `audit_log` table [a-beginners-guide-to-cdc-change-data-capture.md].
- Audit rows must be immutable. Triggers and Envers only INSERT into the audit table; never expose UPDATE/DELETE on `*_AUD` or `book_audit_log` tables to the application user. Treat the audit table as append-only [postgresql-audit-logging-triggers.md, the-best-way-to-implement-an-audit-log-using-hibernate-envers.md].
- Capturing "who" requires the application to push the principal into the DB session. Use Postgres `SET LOCAL var.logged_user`, MySQL `SET @logged_user`, or SQL Server `sp_set_session_context N'loggedUser'` — `SET LOCAL`/session context is mandatory under connection pooling so the variable is wiped at COMMIT/ROLLBACK [postgresql-audit-logging-triggers.md, mysql-audit-logging-triggers.md, sql-server-audit-logging-triggers.md].
- Capture both `dml_timestamp` (per-statement, from `CURRENT_TIMESTAMP`/`SYSUTCDATETIME()`) and `trx_timestamp` (per-transaction, from `transaction_timestamp()`). The trx timestamp lets you reconstruct atomic batches of changes that committed together [audit-log-yugabytedb.md, sql-server-audit-logging-triggers.md].
- Store old/new row state as JSON (`jsonb`, MySQL `JSON_OBJECT`, SQL Server `FOR JSON PATH`). One generic `audit_log(table_name, row_id, old_row_data, new_row_data, dml_type, ...)` schema survives table evolution without ALTERing the audit table for every column added [audit-log-yugabytedb.md, mysql-audit-logging-triggers.md, postgresql-audit-logging-triggers.md].
- Soft-delete and audit must agree on "deletion": with Envers and `@SQLDelete`, the DELETE becomes an UPDATE and is recorded as a `MOD` revision (not `DEL`). Either drop soft-delete in favor of physical delete + audit table, or add an explicit `deleted` column whose flip is treated as the tombstone event [the-best-way-to-implement-an-audit-log-using-hibernate-envers.md].
- Outbox pattern is the only safe way to publish events transactionally from an OLTP app: write the event row to an `outbox` table in the same transaction as the business write, then let Debezium tail that table and forward to Kafka. Avoids dual-write inconsistency between DB and message broker [how-to-extract-change-data-events-from-mysql-to-kafka-using-debezium.md, a-beginners-guide-to-cdc-change-data-capture.md].
- CDC consumers are eventually consistent — Kafka delivers `c`/`u`/`d` events asynchronously after commit. Do not design read paths that require a downstream projection to be up-to-date before returning to the user [how-to-extract-change-data-events-from-mysql-to-kafka-using-debezium.md].

## Decision Trees

### Tree 1: Choose an audit strategy
- Do all writes flow through one Hibernate application?
  - Yes, and you only need who/when on the current row (no history) -> JPA `@EntityListeners` + `@Embeddable Audit { createdBy, createdOn, updatedBy, updatedOn }` populated in `@PrePersist`/`@PreUpdate` [how-to-audit-entity-modifications-using-the-jpa-entitylisteners-embedded-and-embeddable-annotations.md].
  - Yes, and you need full per-revision history queryable from Java -> Hibernate Envers `@Audited` + `ValidityAuditStrategy`, and add Spring Data Envers `RevisionRepository<T, ID, Long>` for repository-style queries [the-best-way-to-implement-an-audit-log-using-hibernate-envers.md, spring-data-envers.md].
  - No, the DB is also written by batch jobs / replication / other services -> database triggers writing a generic JSON `audit_log` table [postgresql-audit-logging-triggers.md, mysql-audit-logging-triggers.md, sql-server-audit-logging-triggers.md].

### Tree 2: Need to stream change events to other systems?
- Need downstream consumers (cache, search, warehouse, microservice) to react to changes?
  - No -> stop, use Envers or triggers for in-DB history only.
  - Yes, and event volume is high or you cannot tolerate OLTP overhead -> Debezium against binlog/WAL/CDC tables (asynchronous, zero impact on writers) [how-to-extract-change-data-events-from-mysql-to-kafka-using-debezium.md, a-beginners-guide-to-cdc-change-data-capture.md].
  - Yes, and you need transactional guarantees that the event was published iff the business write committed -> Outbox table + Debezium tailing the outbox [how-to-extract-change-data-events-from-mysql-to-kafka-using-debezium.md].

### Tree 3: Compliance / forensic audit (who saw or changed what, tamper-evident)?
- Is the audit required to survive an attacker with app-level credentials?
  - Yes -> DB-layer triggers + revoke INSERT/UPDATE/DELETE on the audit table from the app role (only the trigger's SECURITY DEFINER function may write). App-layer Envers is insufficient because anyone who can bypass Hibernate bypasses the audit [postgresql-audit-logging-triggers.md, the-best-way-to-implement-an-audit-log-using-hibernate-envers.md].
  - Need tamper-evident chain -> add a hash column over (previous_hash || row) to your trigger insert; ship audit rows to an immutable store (WORM / append-only S3) via Debezium [a-beginners-guide-to-cdc-change-data-capture.md, how-to-extract-change-data-events-from-mysql-to-kafka-using-debezium.md].

### Tree 4: Choosing the Envers audit strategy
- Mostly write traffic, audit rarely queried -> default strategy (one row per revision in `*_AUD`) [the-best-way-to-implement-an-audit-log-using-hibernate-envers.md].
- Audit queried frequently ("what was the row at time T") -> `ValidityAuditStrategy` (each `*_AUD` row stores `REVEND`, enabling range queries without a self-join) and explicitly set it: `properties.setProperty(EnversSettings.AUDIT_STRATEGY, ValidityAuditStrategy.class.getName())` [spring-data-envers.md, the-best-way-to-implement-an-audit-log-using-hibernate-envers.md].

### Tree 5: YugabyteDB vs PostgreSQL trigger audit
- Single-node Postgres -> `PRIMARY KEY (table_name, row_id, dml_type, dml_timestamp)` [postgresql-audit-logging-triggers.md].
- YugabyteDB (distributed) -> `PRIMARY KEY ((table_name, row_id) HASH, dml_type, dml_timestamp)` to spread audit rows across tablets; also add `@DynamicUpdate` to every audited entity because Yugabyte uses column-level locking and full-column UPDATEs serialize writers [audit-log-yugabytedb.md].

## Anti-patterns: WRONG / CORRECT

### 1. Auditing in the service layer instead of inside the transaction's flush
[the-best-way-to-implement-an-audit-log-using-hibernate-envers.md]

WRONG — audit row is created from the service after `save`, lost if the outer transaction rolls back, or duplicated on retry:
```java
@Transactional
public Post updatePost(Long id, String title) {
    Post post = postRepository.findById(id).orElseThrow();
    post.setTitle(title);
    postRepository.save(post);
    auditService.recordChange("post", id, "title", title); // separate INSERT
    return post;
}
```

CORRECT — annotate the entity with `@Audited` and let Envers write the audit row via its Hibernate event listener inside the same flush:
```java
@Entity
@Audited
@Table(name = "post")
public class Post {
    @Id private Long id;
    private String title;
    // getters / setters
}
```

### 2. Using `@PreUpdate` without symmetric `@PrePersist`
[how-to-audit-entity-modifications-using-the-jpa-entitylisteners-embedded-and-embeddable-annotations.md]

WRONG — `createdOn` is never set, leaving NULLs after every INSERT:
```java
public class AuditListener {
    @PreUpdate
    public void onUpdate(Auditable a) {
        a.getAudit().setUpdatedOn(LocalDateTime.now());
        a.getAudit().setUpdatedBy(LoggedUser.get());
    }
}
```

CORRECT — pair every update hook with an insert hook, and create the embedded `Audit` lazily if absent:
```java
public class AuditListener {
    @PrePersist
    public void onInsert(Auditable a) {
        Audit audit = a.getAudit();
        if (audit == null) { audit = new Audit(); a.setAudit(audit); }
        audit.setCreatedOn(LocalDateTime.now());
        audit.setCreatedBy(LoggedUser.get());
    }
    @PreUpdate
    public void onUpdate(Auditable a) {
        Audit audit = a.getAudit();
        audit.setUpdatedOn(LocalDateTime.now());
        audit.setUpdatedBy(LoggedUser.get());
    }
}
```

### 3. Allowing the app role to UPDATE/DELETE audit rows
[postgresql-audit-logging-triggers.md]

WRONG — `audit_log` is owned by the same role the app connects as, so a bug or attacker can rewrite history:
```sql
CREATE TABLE audit_log (...);
GRANT ALL ON audit_log TO app_user;
```

CORRECT — only the trigger function (running as `SECURITY DEFINER`) writes; the app role gets SELECT only:
```sql
CREATE TABLE audit_log (...);
REVOKE ALL ON audit_log FROM app_user;
GRANT SELECT ON audit_log TO app_user;
ALTER FUNCTION book_audit_trigger_func() SECURITY DEFINER;
```

### 4. Using application-server wall clock instead of DB time
[mysql-audit-logging-triggers.md, audit-log-yugabytedb.md]

WRONG — `LocalDateTime.now()` on the JVM disagrees across nodes, drifts, and is unrelated to commit order:
```java
audit.setUpdatedOn(LocalDateTime.now());
```

CORRECT — let the trigger stamp the time so all rows share one clock and you can sort by commit order:
```sql
INSERT INTO book_audit_log (..., dml_timestamp, trx_timestamp)
VALUES (..., CURRENT_TIMESTAMP, transaction_timestamp());
```

### 5. Listing every column in the audit table
[mysql-audit-logging-triggers.md, postgresql-audit-logging-triggers.md]

WRONG — every new `book` column requires an `ALTER TABLE book_audit_log` and a trigger rewrite:
```sql
CREATE TABLE book_audit_log (
    book_id BIGINT, old_title VARCHAR(255), new_title VARCHAR(255),
    old_author VARCHAR(255), new_author VARCHAR(255),
    old_price_in_cents INT, new_price_in_cents INT, ...
);
```

CORRECT — store the row snapshot as JSON so the schema is stable:
```sql
CREATE TABLE book_audit_log (
    book_id BIGINT NOT NULL,
    old_row_data jsonb,
    new_row_data jsonb,
    dml_type dml_type NOT NULL,
    dml_timestamp timestamp NOT NULL,
    dml_created_by varchar(255) NOT NULL,
    PRIMARY KEY (book_id, dml_type, dml_timestamp)
);
-- trigger body:
INSERT INTO book_audit_log VALUES (NEW.id, to_jsonb(OLD), to_jsonb(NEW),
    'UPDATE', CURRENT_TIMESTAMP, current_setting('var.logged_user'));
```

### 6. Treating application log files as the audit trail
[a-beginners-guide-to-cdc-change-data-capture.md]

WRONG — relying on `logger.info("User {} updated post {}", user, id)` for compliance:
```java
log.info("Post {} updated by {} to '{}'", id, user, title);
postRepository.save(post);
```
Logs rotate, get sampled, contain no row state, and are not in the DB transaction. They cannot answer "what did the row look like at 14:03?".

CORRECT — use Envers or a trigger so the audit record is an actual table row, transactional and queryable:
```java
@Audited @Entity public class Post { ... }
// or DB trigger writing book_audit_log
```

### 7. Dual-writing to DB and Kafka from the service layer
[how-to-extract-change-data-events-from-mysql-to-kafka-using-debezium.md, a-beginners-guide-to-cdc-change-data-capture.md]

WRONG — DB commits, broker publish fails (or vice versa), consumers go out of sync:
```java
@Transactional
public void placeOrder(Order o) {
    orderRepository.save(o);
    kafkaTemplate.send("orders", new OrderPlaced(o.getId())); // not in DB tx
}
```

CORRECT — write to an outbox table in the same transaction, let Debezium tail the outbox and publish:
```java
@Transactional
public void placeOrder(Order o) {
    orderRepository.save(o);
    outboxRepository.save(new OutboxEvent("orders", "OrderPlaced",
        objectMapper.writeValueAsString(new OrderPlaced(o.getId()))));
}
```
Debezium then reads the binlog/WAL row inserted into `outbox` and produces it to Kafka.

### 8. Forgetting `@DynamicUpdate` on audited entities under YugabyteDB
[audit-log-yugabytedb.md]

WRONG — Hibernate emits an UPDATE listing every column, which under Yugabyte's column-level locking serializes writers and floods the audit table with unchanged columns:
```java
@Entity
public class Author {
    @Id private Long id;
    private String firstName, lastName, country;
    private boolean taxTreatyClaiming;
}
```

CORRECT — emit only changed columns; trigger sees only what really changed:
```java
@Entity
@DynamicUpdate
public class Author { ... }
```

### 9. Reusing one connection's session variable for "who" without `SET LOCAL`
[postgresql-audit-logging-triggers.md]

WRONG — under HikariCP the next checkout sees the previous user's value:
```java
session.doWork(c -> update(c, "SET var.logged_user = 'alice'")); // session-wide, leaks
```

CORRECT — scope to the current transaction so COMMIT/ROLLBACK clears it:
```java
session.doWork(c -> update(c, "SET LOCAL var.logged_user = 'alice'"));
```

### 10. Skipping the `RevisionType` filter when reading Envers history
[the-best-way-to-implement-an-audit-log-using-hibernate-envers.md]

WRONG — DELETE revisions return entities with all properties NULL except the id, which crashes downstream code expecting populated fields:
```java
List<Post> history = AuditReaderFactory.get(em)
    .createQuery()
    .forRevisionsOfEntity(Post.class, true, true)
    .add(AuditEntity.id().eq(1L))
    .getResultList();
history.forEach(p -> render(p.getTitle().toUpperCase())); // NPE on DEL row
```

CORRECT — either exclude DELs or branch on `RevisionType`:
```java
List<Object[]> history = AuditReaderFactory.get(em)
    .createQuery()
    .forRevisionsOfEntity(Post.class, false, true)
    .add(AuditEntity.id().eq(1L))
    .add(AuditEntity.revisionType().ne(RevisionType.DEL))
    .getResultList();
```

## Performance Pitfalls

- Envers write amplification: every audited INSERT/UPDATE/DELETE produces (1) the business DML, (2) a `revinfo` INSERT, (3) one `*_AUD` INSERT per audited entity. A flush touching 10 entities issues ~21 statements; batch them with `hibernate.jdbc.batch_size` and `order_inserts=true` [the-best-way-to-implement-an-audit-log-using-hibernate-envers.md].
- Default Envers strategy requires a self-join (`MAX(REV)` per id) to reconstruct the row at time T. Switch to `ValidityAuditStrategy` (adds `REVEND`) when history reads dominate — but it then does an extra UPDATE on the previous `_AUD` row on every change [the-best-way-to-implement-an-audit-log-using-hibernate-envers.md, spring-data-envers.md].
- Synchronous triggers run inside the user's transaction, so every INSERT/UPDATE/DELETE pays the cost of an extra audit INSERT plus JSON serialization (`to_jsonb`, `JSON_OBJECT`, `FOR JSON PATH`). For write-heavy tables this can double commit latency — prefer Debezium when latency budgets are tight [mysql-audit-logging-triggers.md, postgresql-audit-logging-triggers.md, sql-server-audit-logging-triggers.md].
- A single global `audit_log(table_name, row_id, ...)` table becomes a hotspot on Postgres (one heap, one PK index, one WAL stream). On YugabyteDB hash the PK on `(table_name, row_id)` to spread across tablets; on Postgres, partition by `table_name` or by `dml_timestamp` month to keep indexes small [audit-log-yugabytedb.md, postgresql-audit-logging-triggers.md].
- Debezium / log-based CDC requires you to retain binlogs (MySQL `binlog_expire_logs_seconds`), WAL segments (Postgres `wal_keep_size` + replication slot), or SQL Server CDC capture tables. If a consumer lags and the log is reclaimed, the connector must do a full snapshot — keep enough retention or monitor slot lag [how-to-extract-change-data-events-from-mysql-to-kafka-using-debezium.md].
- A Postgres logical replication slot that no one consumes pins WAL forever and will fill the disk. Always drop unused slots (`pg_drop_replication_slot`) and alert on `pg_replication_slots.confirmed_flush_lsn` lag [how-to-extract-change-data-events-from-mysql-to-kafka-using-debezium.md].
- JSON columns in the audit table are larger than narrow columns and harder to index. If you query "all changes to `price_in_cents`", add a generated column (`new_row_data ->> 'price_in_cents'`) with an index — do not full-table-scan the JSON [postgresql-audit-logging-triggers.md, mysql-audit-logging-triggers.md].
- The audit table grows monotonically. Add a retention job that partitions by `dml_timestamp` and drops old partitions (cheap) rather than `DELETE ... WHERE dml_timestamp < ...` (expensive, fragments indexes) [audit-log-yugabytedb.md].
- Spring Data Envers `RevisionRepository.findRevisions(id, Pageable)` executes `COUNT(*) FROM post_AUD WHERE id = ?` for pagination; on hot rows this scans many revisions. Use unpaged or keyset-paginated revision queries for large histories [spring-data-envers.md].
- Outbox tables accumulate rows. The CDC connector should set a marker after publishing, and a separate cleanup job should delete published rows older than N hours — otherwise the outbox becomes the largest table in the schema [how-to-extract-change-data-events-from-mysql-to-kafka-using-debezium.md].
- SQL Server `SESSION_CONTEXT(N'loggedUser')` is per-session, not per-statement; under HikariCP set it at the start of every transaction (Spring `TransactionSynchronization`) so a pooled connection from another tenant cannot leak the previous user into the audit trail [sql-server-audit-logging-triggers.md].

## Citations

- `a-beginners-guide-to-cdc-change-data-capture.md` — Overview of trigger-based vs transaction-log-based CDC and the role of Debezium/Kafka.
- `audit-log-yugabytedb.md` — Distributed audit log on YugabyteDB with hashed PK and `@DynamicUpdate` requirement.
- `how-to-audit-entity-modifications-using-the-jpa-entitylisteners-embedded-and-embeddable-annotations.md` — JPA `@Embeddable Audit` plus `@EntityListeners` for createdBy/updatedBy on the live row.
- `how-to-extract-change-data-events-from-mysql-to-kafka-using-debezium.md` — Running Debezium against MySQL binlog and the `c`/`u`/`d` event payload shape.
- `mysql-audit-logging-triggers.md` — MySQL AFTER INSERT/UPDATE/DELETE triggers writing JSON snapshots into `book_audit_log`.
- `postgresql-audit-logging-triggers.md` — Postgres `plpgsql` audit trigger using `to_jsonb(OLD)`/`to_jsonb(NEW)` and `SET LOCAL` for the logged user.
- `spring-data-envers.md` — Spring Data Envers `RevisionRepository` and enabling `ValidityAuditStrategy`.
- `sql-server-audit-logging-triggers.md` — SQL Server triggers using `FOR JSON PATH` and `SESSION_CONTEXT` for the logged user.
- `the-best-way-to-implement-an-audit-log-using-hibernate-envers.md` — Envers `@Audited`, `RevisionType` semantics, and querying via `AuditReaderFactory`.
