# Connection Pooling and DataSource Management

## Core Principles

- Pool every JDBC `DataSource` in production. Acquiring a physical PostgreSQL/YugabyteDB connection costs 27-233 ms per round trip versus 1.9-47.5 microseconds for a pooled logical connection — a 10,000x speedup at the p99 [`yugabytedb-connection-pooling.md`]. Even Oracle XE's `OracleDataSource` (which provides no pool) collapses under low-latency churn with `ORA-12516 TNS:listener could not find available handler` errors until you front it with HikariCP [`why-you-should-always-use-connection-pooling-with-oracle-xe.md`].
- Prefer HikariCP for new code. It is "probably the fastest connection pooling framework available" with p99 connection acquisition near 0.01 ms versus 3-55 ms for unpooled DataSources [`the-anatomy-of-connection-pooling.md`]. c3p0, Apache DBCP, DBCP2, BoneCP, and Bitronix BTM remain supported by FlexyPool but are legacy choices; do not pick them for greenfield Spring Boot work [`flexypool-2-released.md`].
- Size pools using the Universal Scalability Law, not Amdahl's law. Throughput rises with concurrency only to a peak, after which adding connections degrades it as the database burns cycles coordinating contention [`optimal-connection-pool-size.md`, `the-best-way-to-detect-database-connection-leaks.md` indirectly]. The "right" size is always smaller than operators expect — typically 4-10 for a single application node against a primary [`optimal-connection-pool-size.md`].
- Disable auto-commit at the pool and tell Hibernate. Set `spring.datasource.hikari.auto-commit=false` plus `spring.jpa.properties.hibernate.connection.provider_disables_autocommit=true` so Hibernate skips the `setAutoCommit(false)`/restore dance on every transaction. Without both flags, every `@Transactional` method pays two extra JDBC round trips and Hibernate must acquire the connection eagerly just to inspect auto-commit state [`connection-monitoring-jpa-hibernate.md`].
- For resource-local JPA (the Spring Boot default), the JDBC `Connection` is acquired when the transaction begins and held until commit/rollback. For JTA, the connection is acquired on first `Statement` execution and aggressively released after each one [`connection-monitoring-jpa-hibernate.md` appendix on resource-local vs JTA].
- Delay connection acquisition whenever the transaction performs non-DB work first. Wrap your `DataSource` in Spring's `LazyConnectionDataSourceProxy` so the physical connection is borrowed only when the first SQL statement runs — a transaction that calls a 748 ms REST API before its SELECT will hold the connection for 18 ms instead of 768 ms [`lazyconnectiondatasourceproxy-spring-data-jpa.md`].
- Configure connection-acquisition `connection-timeout` aggressively. Bitronix's 30 s default `acquisitionTimeout` is "way too much for our QoS"; values of 100-1000 ms are typical, with FlexyPool's `IncrementPoolOnTimeoutConnectionAcquisitionStrategy` reacting to overflow events [`connection-pool-sizing-with-flexy-pool.md`, `optimal-connection-pool-size.md`].
- Use FlexyPool as a `DataSource` proxy when you need adaptive sizing, per-pool monitoring (Dropwizard Metrics histograms for `connectionAcquireMillis`, `connectionLeaseMillis`, `concurrentConnectionsHistogram`, `maxPoolSizeHistogram`, `overflowPoolSizeHistogram`, `retryAttemptsHistogram`), or failover via `IncrementPoolOnTimeout` / `RetryAttempts` strategies [`how-does-flexypool-support-the-dropwizard-metrics-package-renaming.md`, `flexypool-2-released.md`].
- Detect connection leaks during testing, not in production. Run a `@BeforeClass` / `@AfterClass` `ConnectionLeakUtil` that queries `information_schema.sessions` (H2), `v$session WHERE status='INACTIVE'` (Oracle), `pg_stat_activity WHERE state ILIKE '%idle%'` (PostgreSQL), or `SHOW PROCESSLIST` (MySQL) and fails the build when idle counts grow across a test class [`the-best-way-to-detect-database-connection-leaks.md`].
- Treat the database `max_connections` as a hard ceiling shared across every application node and proxy. PostgreSQL defaults to 100, MySQL to 151, YugabyteDB to 300 per `yb-tserver`, Oracle XE allows up to ~1528 sessions but cannot serve them — sum per-node `maximum-pool-size` plus PgBouncer/ProxySQL/HAProxy pools and verify the total fits [`optimal-connection-pool-size.md`, `yugabytedb-connection-pooling.md`].
- Front microservice fleets with PgBouncer or ProxySQL when per-node pool sizing becomes impossible. Application nodes then open one cheap TCP connection to the proxy and the physical pool is shared elastically [`yugabytedb-connection-pooling.md`].
- Monitor connection state externally in Java EE / Jakarta EE. Application-server-managed `DataSource`s expose no programmatic resize hooks, so configure FlexyPool via `flexy-pool.properties` with `flexy.pool.data.source.jndi.name` and `flexy.pool.metrics.reporter.jmx.auto.start=true` to surface usage in JMX [`how-to-monitor-a-java-ee-datasource.md`].

## Decision Trees

### Tree 1 — "What pool size should I configure?"
1. Do you have load-test or production telemetry? If no, start with HikariCP's default `maximum-pool-size=10`, then measure [`optimal-connection-pool-size.md`].
2. Add FlexyPool with `IncrementPoolOnTimeoutConnectionAcquisitionStrategy(maxOverflowPoolSize=10, connectionAcquisitionThresholdMillis=25)` and `maximumPoolSize=1` on the underlying HikariCP. Run realistic load; FlexyPool grows the pool only when a connection cannot be acquired inside the threshold and records the equilibrium size [`optimal-connection-pool-size.md`, `connection-pool-sizing-with-flexy-pool.md`].
3. Read the `maxPoolSizeHistogram` and `concurrentConnectionsHistogram`. Pick the smallest value that produces zero `retryAttempts` under peak load (example from the cited test: 4 connections served 64 concurrent transfers in 128 ms; 64 connections took 272 ms because contention moved to the database serializing `REPEATABLE_READ` transactions) [`optimal-connection-pool-size.md`].
4. Multiply by node count and add proxy pools (PgBouncer/HAProxy). If the total exceeds the database's `max_connections`, either scale the database vertically, add read replicas, or insert PgBouncer to share physical connections [`optimal-connection-pool-size.md`, `yugabytedb-connection-pooling.md`].
5. Cap at the Universal Scalability Law peak — beyond it, throughput drops [`optimal-connection-pool-size.md`].

### Tree 2 — "Do I need read/write replica routing?"
1. Are >70% of your transactions read-only (`@Transactional(readOnly = true)`)? If no, route everything to the primary; replication adds complexity without throughput payoff.
2. If yes, deploy single-primary database replication and use an `AbstractRoutingDataSource` that inspects `TransactionSynchronizationManager.isCurrentTransactionReadOnly()` to choose the replica `DataSource`, or front the cluster with ProxySQL/HAProxy that routes based on the JDBC `readOnly` flag [`optimal-connection-pool-size.md` — "How to split connections among multiple application nodes"].
3. The routing only works if Spring/Hibernate actually propagates the read-only flag. That requires eager connection acquisition through `HibernateJpaDialect.beginTransaction` — so you must NOT enable `hibernate.connection.handling_mode=DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION` if you depend on ProxySQL/HAProxy read routing [`lazyconnectiondatasourceproxy-spring-data-jpa.md`].
4. Each replica gets its own pool sized via Tree 1. Replicas share the same `max_connections` budget as the primary.

### Tree 3 — "Eager or delayed connection acquisition?"
1. Default Spring + Hibernate behavior on a read-write `@Transactional` method: eager acquisition because Hibernate must check/disable auto-commit [`lazyconnectiondatasourceproxy-spring-data-jpa.md`, `connection-monitoring-jpa-hibernate.md`].
2. Set `spring.datasource.hikari.auto-commit=false` and `hibernate.connection.provider_disables_autocommit=true`. Hibernate now trusts the pool and acquires the connection lazily on the first SQL — for read-write transactions [`connection-monitoring-jpa-hibernate.md`].
3. For `@Transactional(readOnly = true)` or transactions with a custom isolation level, `HibernateJpaDialect.beginTransaction` still acquires eagerly because it needs to call `setReadOnly(true)` or set the isolation on the physical connection [`lazyconnectiondatasourceproxy-spring-data-jpa.md`].
4. To fix that, either (a) wrap the `DataSource` in Spring's `LazyConnectionDataSourceProxy` — it stores the read-only flag on a `ConnectionProxy` and applies it lazily when the first statement runs; or (b) set `spring.jpa.properties.hibernate.connection.handling_mode=DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION` [`lazyconnectiondatasourceproxy-spring-data-jpa.md`].
5. Prefer (a) `LazyConnectionDataSourceProxy`. Option (b) prevents Spring from propagating the read-only flag (breaking ProxySQL/HAProxy routing) and throws `InvalidIsolationLevelException` when `@Transactional(isolation=...)` is used [`lazyconnectiondatasourceproxy-spring-data-jpa.md`].
6. The most flexible fix is structural: move `@Transactional` boundaries below any non-DB work (REST calls, XML parsing) so connections are borrowed only after the slow non-DB step completes [`lazyconnectiondatasourceproxy-spring-data-jpa.md`].

### Tree 4 — "I suspect a connection leak. What now?"
1. Symptom: pool exhausted, `SQLTransientConnectionException: Connection is not available, request timed out after 30000ms`, or production `idle in transaction` counts climbing in `pg_stat_activity`.
2. Set HikariCP's `leak-detection-threshold` to 2000-5000 ms (default disabled). HikariCP logs a stack trace whenever a connection is held longer than the threshold without being closed.
3. Reproduce in tests using a `ConnectionLeakUtil` that snapshots idle-session counts in `@BeforeClass` and asserts no growth in `@AfterClass`. Use the dialect-specific counter (`H2IdleConnectionCounter`, `OracleIdleConnectionCounter`, `PostgreSQLIdleConnectionCounter`, `MySQLIdleConnectionCounter`) [`the-best-way-to-detect-database-connection-leaks.md`].
4. Fail the CI build on leak — the cited Hibernate ORM suite caught `EntityManagerFactoryClosedTest`, `EntityManagerFactoryUnwrapTest`, `NoCdiAvailableTest`, and `SynchronizationTypeTest` leaking 1-3 connections each [`the-best-way-to-detect-database-connection-leaks.md`].
5. Fix the leak by converting `try { ... } finally { conn.close(); }` to try-with-resources, ensuring `EntityManager.close()` runs (use Spring-managed transactions instead of manual `entityManagerFactory.createEntityManager()`), and ensuring no thread escapes a `@Transactional` boundary via an async callback that retains the JPA session.
6. Periodic "kill idle connections" scripts are "just a band aid approach" — fix the code, do not rely on the database to clean up [`the-best-way-to-detect-database-connection-leaks.md`].

## Anti-patterns: WRONG / CORRECT

### Anti-pattern 1 — auto-commit left enabled [`connection-monitoring-jpa-hibernate.md`, `lazyconnectiondatasourceproxy-spring-data-jpa.md`]

```properties
# WRONG — Hibernate must check and toggle auto-commit on every transaction;
# blocks lazy acquisition; Hypersistence Optimizer flags AutoCommittingConnectionEvent.
spring.datasource.hikari.auto-commit=true
```

```properties
# CORRECT
spring.datasource.hikari.auto-commit=false
spring.jpa.properties.hibernate.connection.provider_disables_autocommit=true
```

### Anti-pattern 2 — no leak detection in HikariCP [`the-best-way-to-detect-database-connection-leaks.md`]

```yaml
# WRONG — silent leaks accumulate until production pool exhaustion
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      # leak-detection-threshold not set => disabled
```

```yaml
# CORRECT — log a stack trace whenever a connection is held > 2s
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      leak-detection-threshold: 2000
```

### Anti-pattern 3 — closing the connection in `finally` instead of try-with-resources [`the-best-way-to-detect-database-connection-leaks.md`, `yugabytedb-connection-pooling.md`]

```java
// WRONG — if an exception in createStatement skips conn assignment, or if
// conn.close() throws and masks the original failure, the connection leaks.
Connection conn = null;
try {
    conn = dataSource.getConnection();
    // ...
} finally {
    if (conn != null) conn.close();
}
```

```java
// CORRECT — JLS guarantees close() runs even on exception; suppressed exceptions are preserved.
try (Connection connection = dataSource.getConnection()) {
    // ...
}
```

### Anti-pattern 4 — missing `connection-timeout` and `max-lifetime` [`connection-pool-sizing-with-flexy-pool.md`, `optimal-connection-pool-size.md`]

```properties
# WRONG — Bitronix default acquisitionTimeout is 30s, "way too much for our QoS";
# connections live forever and may hit stale-network or DB-side idle reaps.
# (No connection-timeout, no max-lifetime, no idle-timeout configured.)
```

```properties
# CORRECT — HikariCP defaults plus explicit ceilings tuned for the QoS
spring.datasource.hikari.connection-timeout=1000
spring.datasource.hikari.max-lifetime=1800000
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.validation-timeout=500
```

### Anti-pattern 5 — using c3p0, DBCP, or DBCP2 in 2020+ greenfield code [`flexypool-2-released.md`, `the-anatomy-of-connection-pooling.md`]

```xml
<!-- WRONG — Apache DBCP and c3p0 remain only for legacy compatibility;
     they are slower than HikariCP at every percentile and their feature sets
     have stagnated. FlexyPool 2 still supports them but does not recommend them. -->
<dependency>
    <groupId>com.mchange</groupId>
    <artifactId>c3p0</artifactId>
    <version>0.9.5.5</version>
</dependency>
```

```xml
<!-- CORRECT -->
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>5.1.0</version>
</dependency>
```

### Anti-pattern 6 — long HTTP call inside `@Transactional` [`lazyconnectiondatasourceproxy-spring-data-jpa.md`]

```java
// WRONG — the JDBC Connection is acquired eagerly and held for the entire
// REST round trip (~750 ms), depriving other transactions of pooled connections.
@Transactional(readOnly = true)
public Product getAsCurrency(Long id, FxCurrency currency) {
    FxRate fxRate = restTemplate.getForObject(FX_RATE_XML_URL, String.class);
    Product product = productRepository.findById(id).orElseThrow();
    return product.convertTo(currency, fxRate);
}
```

```java
// CORRECT — fetch external data first, then enter the transactional boundary
public Product getAsCurrency(Long id, FxCurrency currency) {
    FxRate fxRate = fxRateService.getFxRate();          // no DB connection held
    return transactionalProductService.convert(id, currency, fxRate);
}

@Service
class TransactionalProductService {
    @Transactional(readOnly = true)
    public Product convert(Long id, FxCurrency currency, FxRate fxRate) {
        Product product = productRepository.findById(id).orElseThrow();
        return product.convertTo(currency, fxRate);
    }
}
```

Alternative (less preferred — see Tree 3) — wrap the `DataSource`:

```java
// CORRECT (alternative) — acquire the connection only when the first SQL runs
@Bean
public DataSource dataSource() {
    HikariConfig hikariConfig = new HikariConfig();
    hikariConfig.setAutoCommit(false);
    hikariConfig.setDataSource(dataSourceProvider().dataSource());
    HikariDataSource poolingDataSource = new HikariDataSource(hikariConfig);
    return new LazyConnectionDataSourceProxy(poolingDataSource);
}
```

### Anti-pattern 7 — oversized pool "to be safe" [`optimal-connection-pool-size.md`]

```java
// WRONG — 64 concurrent threads × 64 connections moved contention from the
// pool to the database; REPEATABLE_READ transactions serialized on the server
// and the run took 272 ms instead of 128 ms.
hikariConfig.setMaximumPoolSize(64);
```

```java
// CORRECT — FlexyPool established that 4 connections suffice; throughput doubled.
hikariConfig.setMaximumPoolSize(4);
```

### Anti-pattern 8 — multiple JPA `EntityManagerFactory`s sharing a single pool against the same database, with no awareness of total connection count [`optimal-connection-pool-size.md`, `yugabytedb-connection-pooling.md`]

```yaml
# WRONG — three persistence units each declare maximum-pool-size: 20,
# totaling 60 connections against a PostgreSQL primary whose max_connections=50.
# First two services start fine; the third fails with
# "FATAL: sorry, too many clients already" intermittently.
```

```yaml
# CORRECT — budget per-service pools against the database max_connections,
# leaving headroom for psql, admin tools, and replicas.
# Total: 3 services × 10 pool + 5 admin headroom = 35 < 50
service-a.datasource.hikari.maximum-pool-size: 10
service-b.datasource.hikari.maximum-pool-size: 10
service-c.datasource.hikari.maximum-pool-size: 10
```

If services scale horizontally, front the database with PgBouncer / ProxySQL so application nodes share a fixed physical pool [`yugabytedb-connection-pooling.md`].

### Anti-pattern 9 — `OracleDataSource` (or any vendor DataSource) used without a pool [`why-you-should-always-use-connection-pooling-with-oracle-xe.md`]

```java
// WRONG — under low-latency churn this throws
// ORA-12516: TNS:listener could not find available handler with matching protocol stack
OracleDataSource ds = new OracleDataSource();
ds.setURL(jdbcUrl);
try (Connection c = ds.getConnection()) { /* ... */ }   // repeated in a tight loop
```

```java
// CORRECT — front every vendor DataSource with HikariCP
HikariConfig cfg = new HikariConfig();
cfg.setDataSource(oracleDataSource);
cfg.setMaximumPoolSize(10);
cfg.setAutoCommit(false);
HikariDataSource pool = new HikariDataSource(cfg);
```

### Anti-pattern 10 — querying without a transaction in JPA [`connection-monitoring-jpa-hibernate.md`]

```java
// WRONG — Hibernate acquires a fresh connection per query because the
// Persistence Context is not transactional; two queries = two physical
// connections. Hypersistence Optimizer raises TransactionlessSessionEvent (CRITICAL).
try (Session em = emf.createEntityManager().unwrap(Session.class)) {
    Post post = em.createQuery("select p from Post p where p.id = :id", Post.class)
        .setParameter("id", 1L).getSingleResult();
    long count = (long) em.createQuery("select count(p) from Post p").getSingleResult();
}
```

```java
// CORRECT — wrap in a transaction so both statements share one connection
@Transactional(readOnly = true)
public void loadPosts() {
    Post post = em.find(Post.class, 1L);
    long count = em.createQuery("select count(p) from Post p", Long.class).getSingleResult();
}
```

## Performance Pitfalls

- Holding the JDBC `Connection` across a non-DB call (REST, file I/O, XML parsing, message-broker publish) caps your transaction throughput at `pool_size / external_call_latency` per second. The cited benchmark held a connection 668 ms versus 18 ms after applying `LazyConnectionDataSourceProxy` plus `provider_disables_autocommit` [`lazyconnectiondatasourceproxy-spring-data-jpa.md`].
- Statementless transactions waste a pooled connection for the entire `@Transactional` block when no SQL runs. Strip `@Transactional` from service methods that may short-circuit before touching the DB; Hypersistence Optimizer flags these as `StatementlessConnectionEvent (MAJOR)` [`connection-monitoring-jpa-hibernate.md`].
- Setting `@Transactional(isolation = ...)` or `readOnly = true` forces eager acquisition through `HibernateJpaDialect.beginTransaction` even when `provider_disables_autocommit=true`, because the dialect needs to set the flag on a physical connection. Fix with `LazyConnectionDataSourceProxy` [`lazyconnectiondatasourceproxy-spring-data-jpa.md`].
- Unpooled connection acquisition costs 27-233 ms per round trip on local PostgreSQL/YugabyteDB and grows on remote `yb-master` setups because the catalog metadata must be fetched and cached per-connection [`yugabytedb-connection-pooling.md`].
- Database connections are OS processes in PostgreSQL/YugabyteDB; high churn or oversized pools "put a strain on your database management system" beyond just memory — context-switching the process table dominates [`the-anatomy-of-connection-pooling.md`, `optimal-connection-pool-size.md`].
- Oversized pools degrade throughput due to the Universal Scalability Law's coherence and contention penalties; benchmark showed `maximumPoolSize=64` taking 2.1x the wall-clock time of `maximumPoolSize=4` for the same workload [`optimal-connection-pool-size.md`].
- Default `acquisitionTimeout` values (Bitronix BTM: 30 s) are far too long for interactive QoS — under saturation a request blocks for 30 s before failing, freezing upstream HTTP threads. Use 100-1000 ms and combine with FlexyPool's `IncrementPoolOnTimeoutConnectionAcquisitionStrategy` for elastic recovery [`connection-pool-sizing-with-flexy-pool.md`].
- FlexyPool dynamic proxies add per-call overhead versus decorators; on versions >=1.2.4 the default is the decorator-based `ConnectionDecoratorFactory`, which calls the target method directly. Do not override back to `JdkConnectionProxyFactory` unless you have a specific compatibility need [`how-does-flexypool-support-both-connection-proxies-and-decorators.md`].
- Sharing a single Bitronix `PoolingDataSource` between XA transactions and resource-local Spring tests requires bean aliasing (`<alias name="testDataSource" alias="dataSource"/>`) and `LrcXADataSource` to bridge non-XA drivers like HSQLDB — a common test-time foot-gun that masks production pool semantics [`how-to-monitor-a-java-ee-datasource.md` appendix].
- Connection pool metrics (`connectionLeaseMillis`, `concurrentConnectionsHistogram`, `overflowPoolSizeHistogram`, `retryAttemptsHistogram`) must be exported to a central system (Graphite, Prometheus, JMX-scraped) — the SLF4J reporter alone is useless for spotting saturation in a multi-node fleet [`connection-pool-sizing-with-flexy-pool.md`, `how-does-flexypool-support-the-dropwizard-metrics-package-renaming.md`].

## Citations

- `[connection-monitoring-jpa-hibernate.md]` — Detecting auto-commit, statementless, and transactionless connection events via Hypersistence Optimizer.
- `[connection-pool-sizing-with-flexy-pool.md]` — Using FlexyPool histograms to derive the equilibrium pool size from real workload metrics.
- `[flexypool-2-released.md]` — FlexyPool 2 release notes, Dropwizard Metrics 3 vs 4 support, Java 8 minimum.
- `[how-does-flexypool-support-both-connection-proxies-and-decorators.md]` — Why decorators beat dynamic proxies for `Connection.close` interception.
- `[how-does-flexypool-support-the-dropwizard-metrics-package-renaming.md]` — ServiceLoader-based selection of the Codahale vs Dropwizard metrics implementation.
- `[how-to-monitor-a-java-ee-datasource.md]` — Declarative `flexy-pool.properties` for JNDI-bound Java EE `DataSource`s with JMX reporting.
- `[lazyconnectiondatasourceproxy-spring-data-jpa.md]` — Spring's `LazyConnectionDataSourceProxy` for delayed connection acquisition under read-only transactions.
- `[optimal-connection-pool-size.md]` — Using FlexyPool's `IncrementPoolOnTimeoutConnectionAcquisitionStrategy` to discover the optimal HikariCP `maximumPoolSize`.
- `[the-anatomy-of-connection-pooling.md]` — Pooling-vs-no-pooling benchmark showing HikariCP at p99 ~0.01 ms versus 3-55 ms unpooled.
- `[the-best-way-to-detect-database-connection-leaks.md]` — `ConnectionLeakUtil` with per-dialect idle-session counters wired into JUnit lifecycle.
- `[why-you-should-always-use-connection-pooling-with-oracle-xe.md]` — Reproducing `ORA-12516` on Oracle XE without a pool and the HikariCP fix.
- `[yugabytedb-connection-pooling.md]` — Physical connection cost on YugabyteDB/PostgreSQL (27-233 ms) versus pooled (1.9-47.5 microseconds); PgBouncer as proxy for microservices.
