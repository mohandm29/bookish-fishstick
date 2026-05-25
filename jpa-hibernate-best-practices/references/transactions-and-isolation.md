# Transactions and Isolation Levels

## Core Principles

- Annotate every public service method that touches the database with `@Transactional` — the service layer defines the unit-of-work boundary; without it, each repository call runs in its own auto-commit transaction and atomicity is lost across multi-statement business logic [spring-transaction-best-practices.md].
- Mark read paths with `@Transactional(readOnly = true)` — Spring sets Hibernate's `FlushMode` to `MANUAL` (skipping dirty checks), and the routing infrastructure can divert the call to a read replica, cutting both CPU and primary-DB load [spring-read-only-transaction-hibernate-optimization.md], [read-write-read-only-transaction-routing-spring.md].
- Set `readOnly = true` on the repository interface or a base class so that any method lacking an explicit `@Transactional` defaults to read-only — explicit `@Transactional` on mutating queries then overrides per-method [spring-transaction-best-practices.md].
- Default Spring propagation `REQUIRED` joins any existing transaction; choose `REQUIRES_NEW` only when the inner work must commit independently (audit, outbox) and accept that it suspends the outer transaction and acquires a second physical connection [spring-transactional-annotation.md].
- Never use `Propagation.NESTED` with the standard `JpaTransactionManager` — JPA does not expose savepoints through `EntityTransaction`, so the propagation either degrades to `REQUIRED` or throws; reach for `JdbcTransactionManager` if savepoints are truly required [spring-transactional-annotation.md].
- Treat isolation guarantees as MVCC-specific, not by-name: PostgreSQL `REPEATABLE READ` is snapshot isolation (prevents non-repeatable and phantom reads but not write skew), Oracle exposes only `READ COMMITTED` and `SERIALIZABLE` (snapshot), MySQL InnoDB `REPEATABLE READ` uses next-key locks and behaves differently from PostgreSQL [a-beginners-guide-to-transaction-isolation-levels-in-enterprise-java.md], [non-repeatable-read.md].
- Use `SERIALIZABLE` only when the workflow exhibits write skew that application-level repeatable reads or pessimistic locks cannot cover — under load it raises serialization-failure rates and forces retry logic in every caller [a-beginners-guide-to-transaction-isolation-levels-in-enterprise-java.md].
- Prefer MVCC databases (PostgreSQL, Oracle) with optimistic `@Version` for high-concurrency workloads; 2PL databases (MySQL default, SQL Server without RCSI) serialize readers and writers and amplify lock waits [a-beginners-guide-to-acid-and-database-transactions.md], [a-beginners-guide-to-transaction-isolation-levels-in-enterprise-java.md].
- Avoid distributed `XA`/2PC unless absolutely necessary — JTA requires a transaction manager, doubles round trips per resource, blocks during the prepare phase, and recovery after a coordinator crash is operationally painful; prefer the outbox pattern with `RESOURCE_LOCAL` [jta-transaction-type.md], [resource_local-jpa-transaction-type.md].
- For RESOURCE_LOCAL JPA, always enable `hibernate.connection.provider_disables_autocommit=true` and configure the pool (HikariCP `autoCommit=false`) so Hibernate defers connection acquisition until the first SQL statement — otherwise a connection is grabbed at transaction start just to flip autocommit off [why-you-should-always-use-hibernate-connection-provider_disables_autocommit-for-resource-local-jpa-transactions.md], [spring-transaction-connection-management.md].
- Log the current database transaction id in MDC via the `txid_current()` / `DBMS_TRANSACTION.LOCAL_TRANSACTION_ID` function and a `TransactionSynchronization` callback — this turns "intermittent" deadlock/lock-wait reports into a single greppable correlation id across the application log and the DB log [current-database-transaction-id.md], [log-database-transaction-id-mdc-logging.md], [spring-mdc-transaction-logging.md].
- Read-only transactions still consume a physical connection for the duration of the method, so external HTTP calls, file I/O, message publishing, and long CPU work must happen outside the `@Transactional` boundary [spring-transaction-connection-management.md].

## Decision Trees

### Tree 1: Read-only operation propagation
1. Is the method a pure query? -> `@Transactional(readOnly = true)`.
2. Does the workflow require multiple queries to see the same snapshot? -> add `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` on MVCC databases [a-beginners-guide-to-transaction-isolation-levels-in-enterprise-java.md].
3. Is the read invoked from inside a read-write `@Transactional` method? -> let propagation `REQUIRED` join the existing transaction; do not force `REQUIRES_NEW` (it would acquire a second connection and break the snapshot) [spring-transactional-annotation.md].
4. Do you have a primary/replica topology? -> rely on `LazyConnectionDataSourceProxy` + a routing `DataSource` keyed on `TransactionSynchronizationManager.isCurrentTransactionReadOnly()` to send read-only transactions to the replica [read-write-read-only-transaction-routing-spring.md].

### Tree 2: REQUIRED vs REQUIRES_NEW vs NESTED vs MANDATORY
1. The method must always run inside a caller's transaction (e.g., domain service) -> `Propagation.MANDATORY` so a forgotten outer `@Transactional` fails fast [spring-transactional-annotation.md].
2. The method should join if a transaction exists, otherwise start one (default service method) -> `Propagation.REQUIRED`.
3. The method must commit even if the caller rolls back (audit row, outbox dispatch, retry log) -> `Propagation.REQUIRES_NEW`; accept the second connection and avoid calling it in tight loops [spring-transactional-annotation.md].
4. You need a savepoint inside a JDBC transaction (no JPA flush across the savepoint) -> `Propagation.NESTED` only with `JdbcTransactionManager`; never with `JpaTransactionManager`.
5. The method does no SQL and is a pure utility -> no annotation; do not pay the interceptor + connection-acquisition cost [spring-transaction-connection-management.md].

### Tree 3: Picking isolation level by needed guarantee
1. Do you tolerate non-repeatable reads inside one transaction (typical CRUD)? -> default `READ COMMITTED` is correct on Oracle/PostgreSQL/SQL Server [a-beginners-guide-to-acid-and-database-transactions.md].
2. Do you read the same row multiple times and require a stable value (validation then update)? -> use `@Version` optimistic locking at `READ COMMITTED`, or escalate to `REPEATABLE READ` if the workflow has no version column [non-repeatable-read.md].
3. Do two transactions read disjoint rows then write each other's row (write skew)? -> snapshot isolation (PostgreSQL `REPEATABLE READ`) does NOT prevent it; use `SERIALIZABLE` (SSI in PostgreSQL) or `SELECT ... FOR UPDATE` on the read set [a-beginners-guide-to-transaction-isolation-levels-in-enterprise-java.md].
4. Are you running a trigger that enforces a cross-row invariant? -> the trigger must execute at `SERIALIZABLE` or hold an explicit lock; under `READ COMMITTED` or snapshot isolation it can miss concurrent inserts [postgresql-triggers-isolation-levels.md].

### Tree 4: Cross-aggregate consistency
1. Single aggregate root, single transaction -> use the default isolation plus `@Version` on the root [non-repeatable-read.md].
2. Read multiple aggregates, then write one based on the others (e.g., book a seat if flight has capacity) -> application-level repeatable reads via `@Version` on every aggregate touched; on conflict, retry [a-beginners-guide-to-transaction-isolation-levels-in-enterprise-java.md].
3. Invariant spans many rows and cannot be expressed per-aggregate (uniqueness windows, scheduling) -> escalate to `SERIALIZABLE` and add a retry interceptor for `CannotSerializeTransactionException` [a-beginners-guide-to-transaction-isolation-levels-in-enterprise-java.md].
4. The invariant straddles two datastores -> use the transactional outbox with `RESOURCE_LOCAL`, not JTA/XA [resource_local-jpa-transaction-type.md], [jta-transaction-type.md].

## Anti-patterns: WRONG / CORRECT

### 1. Missing `@Transactional` on a mutating service method
```java
// WRONG — three separate transactions, race condition leaks money
@Service
public class TransferService {
    @Autowired AccountRepository repo;

    public boolean transfer(String from, String to, long cents) {
        long balance = repo.getBalance(from);
        if (balance < cents) return false;
        repo.addBalance(from, -cents);
        repo.addBalance(to, cents);
        return true;
    }
}
```
```java
// CORRECT — single atomic unit of work [spring-transaction-best-practices.md]
@Service
public class TransferService {
    @Autowired AccountRepository repo;

    @Transactional
    public boolean transfer(String from, String to, long cents) {
        Account src = repo.findByIban(from).orElseThrow();
        if (src.getBalance() < cents) return false;
        repo.addBalance(from, -cents);
        repo.addBalance(to, cents);
        return true;
    }
}
```

### 2. `@Transactional` on a read path without `readOnly = true`
```java
// WRONG — Hibernate keeps the loaded-state snapshot for dirty checking,
// holds a writable connection, and routes to the primary DB
@Transactional
public List<PostDTO> topPosts() {
    return postRepo.findTop10();
}
```
```java
// CORRECT — FlushMode.MANUAL, no dirty snapshot, routable to replica
// [spring-read-only-transaction-hibernate-optimization.md],
// [read-write-read-only-transaction-routing-spring.md]
@Transactional(readOnly = true)
public List<PostDTO> topPosts() {
    return postRepo.findTop10();
}
```

### 3. Self-invocation bypasses the Spring proxy
```java
// WRONG — `this.audit(...)` is a direct call, no TransactionInterceptor,
// REQUIRES_NEW is silently ignored
@Service
public class OrderService {
    @Transactional
    public void place(Order o) {
        save(o);
        audit(o);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void audit(Order o) { /* ... */ }
}
```
```java
// CORRECT — call through the proxy (self-injection or a separate bean)
// [spring-transactional-annotation.md]
@Service
public class OrderService {
    @Autowired OrderService self;        // self-injection
    @Autowired AuditService audits;       // or extract a collaborator

    @Transactional
    public void place(Order o) {
        save(o);
        self.audit(o);                    // proxy is invoked
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void audit(Order o) { /* ... */ }
}
```

### 4. Silent rollback because the exception is checked
```java
// WRONG — Spring rolls back only on RuntimeException/Error by default,
// so this IOException commits the half-done work
@Transactional
public void importFile(Path p) throws IOException {
    repo.save(parse(p));
    Files.delete(p); // throws IOException
}
```
```java
// CORRECT — declare the rollback rule explicitly
// [spring-transactional-annotation.md]
@Transactional(rollbackFor = IOException.class)
public void importFile(Path p) throws IOException {
    repo.save(parse(p));
    Files.delete(p);
}
```

### 5. I/O inside the transaction
```java
// WRONG — the HTTP call holds the JDBC connection for seconds,
// connection pool saturates under load
@Transactional
public void publish(Long id) {
    Article a = repo.findById(id).orElseThrow();
    httpClient.post("https://example/notify", a); // blocking I/O
    a.setPublished(true);
}
```
```java
// CORRECT — split: short tx to load, I/O outside, short tx to persist
// [spring-transaction-connection-management.md]
public void publish(Long id) {
    Article a = txTemplate.execute(s ->
        repo.findById(id).orElseThrow());
    httpClient.post("https://example/notify", a);
    txTemplate.executeWithoutResult(s -> {
        Article fresh = repo.findById(id).orElseThrow();
        fresh.setPublished(true);
    });
}
```

### 6. Wrong propagation in a batch loop
```java
// WRONG — REQUIRES_NEW per item opens a fresh connection for each row,
// exhausts the pool and slows the batch to a crawl
@Transactional
public void importAll(List<Row> rows) {
    for (Row r : rows) self.importOne(r);
}
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void importOne(Row r) { repo.save(toEntity(r)); }
```
```java
// CORRECT — single outer tx, chunked flushes
// [spring-transactional-annotation.md]
@Transactional
public void importAll(List<Row> rows) {
    int i = 0;
    for (Row r : rows) {
        repo.save(toEntity(r));
        if (++i % 50 == 0) { em.flush(); em.clear(); }
    }
}
```

### 7. `READ_UNCOMMITTED` "for performance"
```java
// WRONG — exposes dirty data and on most modern DBs gives zero speedup
// because MVCC reads do not block writes anyway
@Transactional(isolation = Isolation.READ_UNCOMMITTED)
public BigDecimal totalSales() { /* ... */ }
```
```java
// CORRECT — default isolation; if the long read is a bottleneck,
// route it to a replica with readOnly = true
// [a-beginners-guide-to-acid-and-database-transactions.md],
// [read-write-read-only-transaction-routing-spring.md]
@Transactional(readOnly = true)
public BigDecimal totalSales() { /* ... */ }
```

### 8. Ignoring write skew under PostgreSQL "REPEATABLE READ"
```java
// WRONG — both transactions read "two doctors on call", both decide it is
// safe to go off duty, both UPDATE different rows; snapshot isolation
// does NOT detect the conflict
@Transactional(isolation = Isolation.REPEATABLE_READ)
public void goOffDuty(Long doctorId) {
    long onCall = repo.countOnCall();
    if (onCall >= 2) repo.setOffDuty(doctorId);
}
```
```java
// CORRECT — use SERIALIZABLE (SSI) with retry, or take an explicit lock
// [a-beginners-guide-to-transaction-isolation-levels-in-enterprise-java.md]
@Retryable(value = CannotSerializeTransactionException.class, maxAttempts = 3)
@Transactional(isolation = Isolation.SERIALIZABLE)
public void goOffDuty(Long doctorId) {
    long onCall = repo.countOnCall();
    if (onCall >= 2) repo.setOffDuty(doctorId);
}
```

### 9. RESOURCE_LOCAL without disabling pool autocommit
```java
// WRONG — Hibernate eagerly acquires the connection at tx.begin() just to
// set autocommit=false; long service method holds the connection
@Transactional
public Report build(Long id) {
    Report r = repo.findById(id).orElseThrow();
    crunch(r); // CPU-bound, no SQL
    return r;
}
```
```properties
# CORRECT — pool already creates connections with autoCommit=false,
# Hibernate defers acquisition until the first statement
# [why-you-should-always-use-hibernate-connection-provider_disables_autocommit-for-resource-local-jpa-transactions.md]
# [spring-transaction-connection-management.md]
spring.datasource.hikari.auto-commit=false
spring.jpa.properties.hibernate.connection.provider_disables_autocommit=true
```

### 10. JTA/XA when an outbox would do
```java
// WRONG — distributed 2PC across DB and JMS broker; in-doubt transactions
// on coordinator crash require manual recovery
@Transactional("jtaTransactionManager")
public void place(Order o) {
    repo.save(o);
    jmsTemplate.convertAndSend("orders", o);
}
```
```java
// CORRECT — single RESOURCE_LOCAL tx persists order + outbox row;
// a separate poller publishes from the outbox
// [resource_local-jpa-transaction-type.md], [jta-transaction-type.md]
@Transactional
public void place(Order o) {
    repo.save(o);
    outbox.save(new OutboxEvent("orders", o));
}
```

## Performance Pitfalls

- Long transactions hold MVCC snapshots open, bloating PostgreSQL's heap (autovacuum cannot reclaim row versions still visible to the snapshot) and growing Oracle's UNDO segments — keep transactions short and never start one before a user think-time pause [a-beginners-guide-to-acid-and-database-transactions.md].
- Open Session In View binds the `EntityManager` and the JDBC connection to the HTTP thread for the entire response, including view rendering; disable `spring.jpa.open-in-view=false` and define explicit transaction boundaries [spring-transaction-connection-management.md].
- Without `readOnly = true`, Hibernate stores a hydrated loaded-state array for every entity to support dirty checking — large read paths waste heap and CPU comparing snapshots that will never produce an `UPDATE` [spring-read-only-transaction-hibernate-optimization.md].
- Eager connection acquisition (default unless `provider_disables_autocommit=true` is set with a pool that already disables autocommit) means the connection is held from `@Transactional` entry through every non-SQL line in the method, dropping pool capacity by an order of magnitude under load [spring-transaction-connection-management.md], [why-you-should-always-use-hibernate-connection-provider_disables_autocommit-for-resource-local-jpa-transactions.md].
- `REQUIRES_NEW` suspends the outer transaction and grabs a second physical connection from the same pool; using it in a loop is a classic way to deadlock against your own pool [spring-transactional-annotation.md].
- `SERIALIZABLE` under PostgreSQL SSI tracks read/write dependencies and aborts with `serialization_failure`; without a retry interceptor the failure surfaces to the user [a-beginners-guide-to-transaction-isolation-levels-in-enterprise-java.md].
- MySQL InnoDB `REPEATABLE READ` takes next-key (gap) locks on range scans, blocking concurrent inserts that PostgreSQL would let through — benchmark before assuming the named level behaves identically across vendors [a-beginners-guide-to-transaction-isolation-levels-in-enterprise-java.md], [non-repeatable-read.md].
- MDC transaction-id logging via `txid_current()` adds one round trip per transaction; cache it inside a `TransactionSynchronization` `beforeCommit` and clear it in `afterCompletion` so each transaction pays once, not per log line [current-database-transaction-id.md], [spring-mdc-transaction-logging.md].
- Triggers that run inside an application transaction inherit its isolation level — a trigger written assuming `SERIALIZABLE` will silently miss concurrent changes when the app uses the default `READ COMMITTED`; document the required isolation alongside the trigger [postgresql-triggers-isolation-levels.md].
- Routing read-only transactions to a replica requires `LazyConnectionDataSourceProxy` in front of the routing `DataSource`; otherwise the connection is acquired before Spring knows the transaction is read-only and routing always picks the primary [read-write-read-only-transaction-routing-spring.md].

- Acquiring a JDBC connection per repository call (no service-layer `@Transactional`) means N calls = N connection check-outs from the pool, N autocommit toggles, N TLS handshakes if the pool is remote — collapse them into one outer transaction [spring-transaction-best-practices.md], [spring-transaction-connection-management.md].
- A read-only transaction routed to a replica still counts against the replica's connection pool; size both pools and monitor replication lag before promoting reports to replica-only [read-write-read-only-transaction-routing-spring.md].
- `setRollbackOnly()` on a participating transaction taints the outer transaction; if the caller swallows the inner exception, the outer commit throws `UnexpectedRollbackException` at the end of the request — design the boundary so the inner work either uses `REQUIRES_NEW` or propagates the failure [spring-transactional-annotation.md].

## Worked Configurations

### Routing read-only transactions to a replica
```java
// Spring Boot configuration that diverts @Transactional(readOnly = true)
// to a read replica DataSource. Required: LazyConnectionDataSourceProxy
// in front of the router, otherwise the connection is acquired before
// Spring sets the read-only flag on the transaction synchronization.
// [read-write-read-only-transaction-routing-spring.md],
// [spring-transaction-connection-management.md]
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource routingDataSource(
            @Qualifier("primary") DataSource primary,
            @Qualifier("replica") DataSource replica) {

        AbstractRoutingDataSource router = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return TransactionSynchronizationManager
                    .isCurrentTransactionReadOnly() ? "replica" : "primary";
            }
        };
        router.setTargetDataSources(Map.of(
            "primary", primary,
            "replica", replica
        ));
        router.setDefaultTargetDataSource(primary);
        return router;
    }

    @Bean
    @Primary
    public DataSource dataSource(DataSource routingDataSource) {
        return new LazyConnectionDataSourceProxy(routingDataSource);
    }
}
```

### Propagating the DB transaction id to MDC
```java
// Registers a TransactionSynchronization that queries txid_current() on
// the first SQL statement of every transaction and stores the id in MDC.
// [current-database-transaction-id.md], [log-database-transaction-id-mdc-logging.md],
// [spring-mdc-transaction-logging.md]
@Component
public class TxIdMdcAspect {

    @Autowired private JdbcTemplate jdbc;

    @EventListener
    public void onTxStart(TransactionStartedEvent e) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    String txId = jdbc.queryForObject(
                        "SELECT txid_current()", String.class);
                    MDC.put("dbTxId", txId);
                }
                @Override public void afterCompletion(int status) {
                    MDC.remove("dbTxId");
                }
            });
    }
}
```

### Retrying serialization failures
```java
// SERIALIZABLE under PostgreSQL SSI aborts conflicting transactions with
// SQLState 40001. Wrap the call so the retry happens around the entire
// @Transactional method (not inside it).
// [a-beginners-guide-to-transaction-isolation-levels-in-enterprise-java.md]
@Service
public class SchedulingService {

    @Retryable(
        value = { CannotSerializeTransactionException.class },
        maxAttempts = 5,
        backoff = @Backoff(delay = 25, multiplier = 2.0))
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void assignSlot(Long slotId, Long userId) {
        // read other bookings, decide, write — SSI guarantees serial order
    }
}
```

## Additional Decision Tree: Logging and observability

1. Need to correlate Java log entries with DB-side slow-query or deadlock reports? -> capture `txid_current()` (PostgreSQL) / `DBMS_TRANSACTION.LOCAL_TRANSACTION_ID` (Oracle) once per transaction via a `TransactionSynchronization`, push it into MDC, clear it in `afterCompletion` [current-database-transaction-id.md], [log-database-transaction-id-mdc-logging.md], [spring-mdc-transaction-logging.md].
2. Need to know whether a given service call hit the primary or the replica? -> log `TransactionSynchronizationManager.isCurrentTransactionReadOnly()` from the same synchronization [read-write-read-only-transaction-routing-spring.md].
3. Need to detect long transactions before they hurt? -> hook a `beforeCommit` callback that measures wall-clock duration against a threshold and emits a metric.

## Additional Decision Tree: Choosing JTA vs RESOURCE_LOCAL

1. Single relational database, no JMS broker in the same transaction -> `RESOURCE_LOCAL`; configure `JpaTransactionManager` [resource_local-jpa-transaction-type.md].
2. Multiple databases or DB + transactional JMS in the same atomic unit -> evaluate the outbox pattern first; only fall back to JTA/XA if the messaging system cannot be replaced and at-least-once delivery is not acceptable [jta-transaction-type.md], [resource_local-jpa-transaction-type.md].
3. Already running inside a Jakarta EE container with a managed `UserTransaction` -> `JTA`; do not mix `RESOURCE_LOCAL` and `JTA` in the same persistence unit.

## Citations

- `a-beginners-guide-to-acid-and-database-transactions.md` — ACID properties, isolation phenomena, default isolation per vendor.
- `a-beginners-guide-to-transaction-isolation-levels-in-enterprise-java.md` — Phenomena vs guarantees, MVCC vs 2PL, write skew, SSI in PostgreSQL.
- `current-database-transaction-id.md` — Fetching the DB transaction id (`txid_current`, `DBMS_TRANSACTION`).
- `jta-transaction-type.md` — JTA/XA transaction manager, 2PC cost and recovery pitfalls.
- `log-database-transaction-id-mdc-logging.md` — Putting the DB transaction id into the MDC for log correlation.
- `non-repeatable-read.md` — Non-repeatable read anomaly and how each isolation level addresses it.
- `postgresql-triggers-isolation-levels.md` — Why trigger logic must consider the calling transaction's isolation.
- `read-write-read-only-transaction-routing-spring.md` — Replica routing via `AbstractRoutingDataSource` + `LazyConnectionDataSourceProxy`.
- `resource_local-jpa-transaction-type.md` — RESOURCE_LOCAL vs JTA selection, outbox pattern over XA.
- `spring-mdc-transaction-logging.md` — Spring `TransactionSynchronization` callbacks for MDC population.
- `spring-read-only-transaction-hibernate-optimization.md` — `readOnly = true` propagating to Hibernate `FlushMode.MANUAL` and read-only entities.
- `spring-transaction-best-practices.md` — Service-layer transaction boundaries, Flexcoin race condition, atomicity rules.
- `spring-transaction-connection-management.md` — Eager vs deferred connection acquisition, OSIV anti-pattern, pool sizing.
- `spring-transactional-annotation.md` — Propagation, isolation, rollback rules, proxy semantics of `@Transactional`.
- `why-you-should-always-use-hibernate-connection-provider_disables_autocommit-for-resource-local-jpa-transactions.md` — `hibernate.connection.provider_disables_autocommit=true` to defer connection acquisition.
