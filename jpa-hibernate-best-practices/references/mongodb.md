# MongoDB Performance and Patterns

## Core Principles

- Prefer the aggregation framework over map/reduce for analytics; MongoDB's pipeline operators (`$match`, `$group`, `$sort`, `$project`) execute natively in C++ and trivially aggregate ~387K documents/second on commodity hardware, while map/reduce pays a JavaScript-engine penalty per document [mongodb-facts-lightning-speed-aggregation.md].
- Always create an explicit ascending or descending index on every field used in `$match`, equality filters, range filters, or sort keys; the `_id` index alone is rarely sufficient. The time-series workload only became viable after `db.randomData.ensureIndex({"created_on": 1})` was added [mongodb-time-series-introducing-the-aggregation-framework.md].
- Run `cursor.explain()` (or `aggregate(..., { explain: true })`) on every non-trivial query during development; verify `"cursor": "BtreeCursor <indexName>"` rather than `"BasicCursor"`, and inspect `indexBounds` to confirm the planner narrowed the scan [mongodb-time-series-introducing-the-aggregation-framework.md].
- Design the document model to match the dominant read pattern: embed when child data is bounded and always accessed with the parent (one-to-few, one-to-many with hard upper bound); reference (store ObjectIds) when children are independently queried, unbounded, or grow over time [mongodb-and-the-fine-art-of-data-modelling.md].
- Bucket time-series data: instead of one document per event, store many events inside a parent document keyed by `_id = epoch_ms_of_bucket_start` with a `values` array. This collapsed the per-document overhead from 64 bytes to 44 bytes and let the working set fit RAM [mongodb-and-the-fine-art-of-data-modelling.md].
- Implement optimistic locking on every multi-writer document with a `@Version` field (Spring Data `org.springframework.data.annotation.Version`), letting the framework issue `update({_id, version}, {$set:..., $inc:{version:1}})` and throw `OptimisticLockingFailureException` when no document matched [mongodb-optimistic-locking.md].
- Pair optimistic locking with an AOP-driven retry aspect (`@Retry(times=10, on=OptimisticLockingFailureException.class)`) so transient lost-update races recover automatically instead of bubbling up; the same aspect works unchanged for the JPA `OptimisticLockException` [optimistic-locking-retry-with-mongodb.md].
- Batch writes aggressively: the 80K-inserts/second benchmark used `db.randomData.insert(batchDocuments)` with batches of 5000; never issue per-document inserts inside a tight loop when `insertMany` / `bulkWrite` is available [mongodb-facts-80000-insertssecond-on-commodity-hardware.md].
- Set an explicit `writeConcern` (`{w:1}` or `{w:"majority"}`) on every write path — the deprecated `safe=true` flag is no longer enough; never rely on a default that may be `{w:0}` (fire-and-forget) for data you cannot lose [mongodb-facts-80000-insertssecond-on-commodity-hardware.md].
- Manage schema drift with versioned migration scripts via Mongeez (XML changelog + JS files named `v1_1__initial_data.js`, `v1_2__update_products.js`); the runner records executed change sets in a `mongeez` collection so each script runs exactly once [mongodb-incremental-migration-scripts.md].
- Use capped collections (e.g. 100,000 docs) for bounded telemetry such as host latency measurements — fixed size eliminates retention scripts and disk-exhaustion risk [nosql-is-not-just-about-bigdata.md].
- Run integration tests against a real `mongod` via Flapdoodle's `de.flapdoodle.embed.mongo`; an in-memory SQL-like substitute will not catch aggregation, index, or write-concern bugs that only surface in the real engine [integration-testing-done-right-with-embedded-mongodb.md].

## Decision Trees

### Embed vs. reference
```
Is the child always loaded with the parent, AND
  is the child set bounded by a known small N (< few hundred), AND
  is the child not queried independently?
  YES -> embed as subdocument or array
  NO  -> is the child mutated frequently while the parent is read-mostly?
          YES -> reference (store child._id on parent or parent._id on child)
          NO  -> is total document size at risk of exceeding 16 MB?
                  YES -> reference (split)
                  NO  -> embed
```
Source: [mongodb-and-the-fine-art-of-data-modelling.md].

### Update strategy under contention
```
Will multiple writers touch the same document?
  NO  -> plain $set / $inc, default write concern w:1
  YES -> add @Version field
          Use repository.save() (Spring Data adds version check)
          Wrap caller in @Retry(times=N, on=OptimisticLockingFailureException.class)
          Pure-counter / append-only?
            YES -> consider field-scoped atomic operator ($inc, $push) instead of read-modify-write
            NO  -> keep optimistic version + retry loop
```
Source: [mongodb-optimistic-locking.md], [optimistic-locking-retry-with-mongodb.md].

### Aggregation pipeline ordering
```
1. $match FIRST  -> reduces documents using an index (BtreeCursor)
2. $project NEXT -> strip fields not needed by later stages (smaller in-memory docs)
3. $group        -> in-memory grouping; pre-filter to keep set < RAM
4. $sort LAST    -> on already-small grouped result
If $sort must run on the raw collection, ensure the sort key is indexed in the same order as $match key.
If result > 100 MB, pass { allowDiskUse: true } (2.6+).
If result > 16 MB and you need a collection, append $out:"name".
```
Source: [mongodb-time-series-introducing-the-aggregation-framework.md], [mongodb-2-6-is-out.md].

### Index type pick
```
Equality + range on same field          -> single-field B-tree index
Equality on A, range/sort on B          -> compound index {A:1, B:1} (ESR rule: Equality, Sort, Range)
Field absent from most documents        -> sparse index (only docs that have the field are indexed)
TTL expiry on createdAt                 -> {createdAt:1} with expireAfterSeconds
Multiple ad-hoc OR queries on diff cols -> rely on index intersection (2.6+) over many small single-field indexes
Geospatial                              -> 2dsphere
Full-text search                        -> text index (one per collection)
```
Source: [mongodb-2-6-is-out.md], [a-beginners-guide-to-mongodb-performance-turbocharging.md].

### Bulk write ordering
```
Order-independent writes (e.g. event ingestion)?
  YES -> bulkWrite(ops, { ordered: false })  // continues past per-doc errors, parallel internally
  NO  -> bulkWrite(ops, { ordered: true })   // stops at first failure, preserves causal order
Batch size: aim for 1000-5000 ops; never send 50K+ in one batch (16 MB BSON limit risk)
```
Source: [mongodb-facts-80000-insertssecond-on-commodity-hardware.md], [mongodb-2-6-is-out.md].

## Anti-patterns: WRONG / CORRECT

### 1. Unindexed query fields (COLLSCAN)
WRONG — relying on `_id` only and filtering on `created_on`:
```js
// No index on created_on -> full collection scan
db.randomData.find({ created_on: { $gte: from, $lt: to } });
// explain shows "cursor": "BasicCursor"
```
CORRECT:
```js
db.randomData.ensureIndex({ created_on: 1 });
db.randomData.find({ created_on: { $gte: from, $lt: to } }).explain();
// "cursor": "BtreeCursor created_on_1", "indexBounds": { "created_on": [[from, to]] }
```
Source: [mongodb-time-series-introducing-the-aggregation-framework.md], [a-beginners-guide-to-mongodb-performance-turbocharging.md].

### 2. Unbounded array growth in a document
WRONG — every event appended to one parent forever:
```js
db.sensor.update(
  { _id: sensorId },
  { $push: { readings: { ts: new Date(), v: 0.42 } } },
  { upsert: true }
);
// After months: document exceeds 16 MB and all writes start failing.
```
CORRECT — bucket by time window, cap the bucket:
```js
const bucketStart = Math.floor(Date.now() / 60000) * 60000; // 1-minute bucket
db.sensor.update(
  { _id: { sensor: sensorId, bucket: bucketStart }, count: { $lt: 200 } },
  { $push: { values: { ts: new Date(), v: 0.42 } }, $inc: { count: 1 } },
  { upsert: true }
);
```
Source: [mongodb-and-the-fine-art-of-data-modelling.md].

### 3. Fire-and-forget write concern
WRONG:
```js
// Driver default historically was {w: 0} -> server may silently drop the write
db.product.insert(product);
```
CORRECT:
```js
db.product.insertOne(product, { writeConcern: { w: "majority", j: true } });
```
Source: [mongodb-facts-80000-insertssecond-on-commodity-hardware.md].

### 4. Missing retry on optimistic-lock conflict
WRONG — exception propagates, user sees 500:
```java
public Product updateName(Long id, String name) {
    Product p = productRepository.findOne(id);
    p.setName(name);
    return productRepository.save(p); // throws OptimisticLockingFailureException
}
```
CORRECT — annotated with retry aspect:
```java
@Retry(times = 10, on = OptimisticLockingFailureException.class)
public Product updateName(Long id, String name) {
    Product p = productRepository.findOne(id);
    p.setName(name);
    return productRepository.save(p);
}
```
Source: [mongodb-optimistic-locking.md], [optimistic-locking-retry-with-mongodb.md].

### 5. Per-document insert loop
WRONG:
```python
for doc in fifty_million_docs:
    collection.insert_one(doc)   # ~3K inserts/sec, network round-trip per doc
```
CORRECT — batched writes:
```python
batch = []
for i, doc in enumerate(fifty_million_docs):
    batch.append(doc)
    if len(batch) == 5000:
        collection.insert_many(batch, ordered=False)
        batch = []
if batch:
    collection.insert_many(batch, ordered=False)
# Measured: 80,000+ inserts/sec on commodity SSD
```
Source: [mongodb-facts-80000-insertssecond-on-commodity-hardware.md].

### 6. JavaScript-evaluated query operators
WRONG — `$where` runs server-side JavaScript, bypasses indexes:
```js
db.product.find({ $where: "this.price * this.quantity > 1000" });
```
CORRECT — compute the derived value at write time or use aggregation `$expr`:
```js
// Option A: store totalValue and index it
db.product.find({ totalValue: { $gt: 1000 } });
// Option B (3.6+): $expr lets the planner use indexes on direct field refs
db.product.find({ $expr: { $gt: [ { $multiply: ["$price", "$quantity"] }, 1000 ] } });
```
Source: [mongodb-2-6-is-out.md].

### 7. Aggregation result > 16 MB or > 100 MB sort
WRONG (pre-2.6 idiom still found in legacy code):
```js
var r = db.randomData.aggregate([ { $group: { _id: "$tag", count: { $sum: 1 } } } ]);
// 2.4: throws "aggregation result exceeds maximum document size (16MB)"
```
CORRECT — use cursor + `allowDiskUse` + `$out` when materializing:
```js
db.randomData.aggregate(
  [ { $group: { _id: "$tag", count: { $sum: 1 } } },
    { $sort: { count: -1 } },
    { $out: "tagCounts" } ],
  { allowDiskUse: true, cursor: { batchSize: 1000 } }
);
```
Source: [mongodb-2-6-is-out.md].

### 8. Schema migration done manually in production
WRONG — DBA runs ad-hoc shell scripts at deploy time; no record of what ran where.
```js
db.product.update({}, { $set: { currency: "USD" } }, { multi: true }); // run once... or twice?
```
CORRECT — versioned Mongeez change set, executed by the application at startup:
```js
// v1_2__update_products.js
//mongeez formatted javascript
//changeset system:v1_2
db.product.update(
  { name: 'TV' },
  { $inc: { price: -10, version: 1 } },
  { multi: true }
);
```
Mongeez records each `changeId` in the `mongeez` collection so the script never runs twice.
Source: [mongodb-incremental-migration-scripts.md].

### 9. Read-modify-write where an atomic operator would suffice
WRONG — lost-update race even with retries:
```java
Product p = repo.findOne(id);
p.setQuantity(p.getQuantity() - 1);
repo.save(p);
```
CORRECT — single-statement atomic decrement, no version check needed for the counter:
```java
mongoTemplate.updateFirst(
    Query.query(Criteria.where("_id").is(id).and("quantity").gte(1)),
    new Update().inc("quantity", -1),
    Product.class);
```
Source: [mongodb-optimistic-locking.md].

### 10. Testing data-access against H2 / in-memory SQL substitute
WRONG — JPA tests run on H2 but production is MongoDB; aggregation pipelines, BSON types, write concerns, and index behavior are never exercised.
CORRECT — Flapdoodle embedded `mongod` bound to the test-suite lifecycle:
```xml
<plugin>
  <groupId>com.github.joelittlejohn.embedmongo</groupId>
  <artifactId>embedmongo-maven-plugin</artifactId>
  <version>${mongo.test.version}</version>
  <configuration>
    <port>${embedmongo.port}</port>
    <databaseDirectory>${project.build.directory}/mongotest</databaseDirectory>
  </configuration>
  <executions>
    <execution><goals><goal>start</goal></goals></execution>
    <execution><goals><goal>stop</goal></goals></execution>
  </executions>
</plugin>
```
Each test JVM gets its own forked `mongod` process on a unique port, exercising the real engine.
Source: [integration-testing-done-right-with-embedded-mongodb.md].

## Performance Pitfalls

- Working set larger than RAM: once indexes + hot data exceed physical memory, MongoDB pages from disk and throughput collapses. Monitor `serverStatus().workingSet` and either shrink the model (bucket time-series, drop unused indexes) or scale RAM/shards before optimizing queries [a-beginners-guide-to-mongodb-performance-turbocharging.md].
- Forgetting to compact after large deletes/migrations: the time-series collection reclaimed ~800 MB of index space via `db.runCommand({compact: "randomData"})`. Bloated `numExtents` cause unnecessary I/O even when logical size is small [mongodb-time-series-introducing-the-aggregation-framework.md].
- Pre-loading the wrong thing with `touch`: warming `index:true, data:true` blindly evicts the actual hot working set. Profile first — for the time-series read pattern, preloading only `data` (skipping the `_id` index) outperformed loading everything [a-beginners-guide-to-mongodb-performance-turbocharging.md].
- Over-indexing: every index inflates writes and the working set. The time-series workload deliberately ran with only `_id` + `created_on`; adding speculative indexes would have pushed the working set past 8 GB RAM and triggered swapping [mongodb-time-series-introducing-the-aggregation-framework.md].
- `$group` before `$match`: grouping the entire collection in memory before filtering will OOM on any non-trivial dataset. Always `$match` (against an indexed field) first to shrink the pipeline input [mongodb-time-series-introducing-the-aggregation-framework.md].
- Ignoring the 16 MB BSON document limit: it caps both individual documents and pre-2.6 aggregation results. Design embedded arrays with a hard size budget; on 2.6+, switch aggregations to cursor mode and `$out` for materialized results [mongodb-2-6-is-out.md].
- Synchronous single-threaded inserts under-utilize MongoDB: a 4-core machine hit 80K inserts/sec only when multiple writer processes ran in parallel; one Python writer alone managed 29K [mongodb-facts-80000-insertssecond-on-commodity-hardware.md].
- Premature sharding: 50 million documents is not "big data" — a single replica set with a properly sized working set handles it. Exhaust modeling, indexing, and bucketing improvements before introducing shard keys [nosql-is-not-just-about-bigdata.md], [mongodb-facts-80000-insertssecond-on-commodity-hardware.md].
- Forcing MongoDB into a relational shape: storing highly normalized data with multi-collection `$lookup` joins replicates SQL pain without SQL's planner. Use MongoDB where polyglot persistence pays off — capped telemetry, persistent caches, JSON-native time series — and keep relational workloads on a RDBMS [nosql-is-not-just-about-bigdata.md].
- No retry budget on optimistic-lock loops: an unbounded retry under heavy contention becomes a livelock. Cap with `@Retry(times=10)` and let the caller surface a clear error after the budget is exhausted [optimistic-locking-retry-with-mongodb.md].

## Citations

- `[a-beginners-guide-to-mongodb-performance-turbocharging.md]` — Working-set sizing, `touch` command, index vs data preload trade-offs.
- `[integration-testing-done-right-with-embedded-mongodb.md]` — Flapdoodle `de.flapdoodle.embed.mongo` plugin for forked-mongod integration tests.
- `[mongodb-2-6-is-out.md]` — 2.6 aggregation cursor, `allowDiskUse`, `$out`, bulk operations, index intersection.
- `[mongodb-and-the-fine-art-of-data-modelling.md]` — Time-bucket compaction, embed-vs-reference, 16 MB document budget.
- `[mongodb-facts-80000-insertssecond-on-commodity-hardware.md]` — Batched inserts, write-concern impact, parallel-writer scaling.
- `[mongodb-facts-lightning-speed-aggregation.md]` — Aggregation framework throughput (387K docs/sec) vs map/reduce.
- `[mongodb-incremental-migration-scripts.md]` — Mongeez versioned change sets and `mongeez` collection bookkeeping.
- `[mongodb-optimistic-locking.md]` — Spring Data `@Version`, lost-update scenarios.
- `[mongodb-time-series-introducing-the-aggregation-framework.md]` — `$match`/`$project`/`$group`/`$sort` ordering, `explain()`, compact.
- `[nosql-is-not-just-about-bigdata.md]` — Polyglot persistence, capped collections, persistent cache pattern.
- `[optimistic-locking-retry-with-mongodb.md]` — `@Retry` annotation, AOP aspect, retry-budget pattern.
