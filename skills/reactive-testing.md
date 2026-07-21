---
name: reactive-testing
description: >
  Write tests for the BFF service — handler integration tests, service unit tests,
  client WireMock tests, and validator tests. Use when: writing tests for a new feature,
  adding test coverage for a handler or service, setting up WireMock stubs, or writing
  StepVerifier assertions for reactive chains.
---

# Write Reactive Tests for the  BFF

## When to Use
- Writing tests for a new or modified  handler (integration test)
- Writing unit tests for a service class with mocked dependencies
- Testing a WebClient-based client against WireMock
- Writing validator tests
- Setting up WireMock stub data for downstream APIs

## Test Categories

This skill covers four test types, ordered by how frequently they are needed:

| Type | Annotation | Purpose |
|---|---|---|
| Handler integration test | `@VerticalSliceTest` + extends `MainTest` | Full end-to-end through  routing |
| Service unit test | `@ExtendWith(MockitoExtension.class)` | Business logic with mocked dependencies |
| Client integration test | `@WireMockTest` | WebClient against real HTTP stubs |
| Validator unit test | Plain JUnit 5 | Input validation rules |

## Mandatory Test Variation Pattern (All APIs)

When a feature introduces or modifies an API endpoint, create and keep all applicable variations below.
Do not replace one variation with another; keep existing unit tests and add missing integration/smoke coverage.

Required by default for each API endpoint:

- Handler integration test: `{Feature}HandlerIntegrationTest`
    - `@VerticalSliceTest`, extends `MainTest`, uses `WebTestClient`
    - Validates request/response envelope with `jsonPath`
    - Includes downstream WireMock stubs and explicit verification where relevant
- Service unit test: `{Feature}ServiceTest`
    - `@ExtendWith(MockitoExtension.class)` with mocked dependencies
    - Uses `StepVerifier` for success and error branches
- Service integration test: `{Feature}ServiceIntegrationTest`
    - Spring context integration style (`@VerticalSliceTest` unless a narrower setup is intentional)
    - Uses real repositories and test profile DB/Redis/WireMock setup
    - Verifies orchestration behavior across repository + downstream integration points
- API smoke test: Karate feature under `src/test/java/karate/**`
    - At least one happy path and one high-value negative path per endpoint

Naming conventions:

- Unit tests: `*Test.java`
- Integration tests: `*IntegrationTest.java`
- Smoke runner: `*SmokeRunner*.java`
- Karate features: `*-smoke.feature`

Minimum assertion quality for integration and smoke:

- Prefer field-level assertions (`jsonPath` / structured assertions), avoid `toString().contains(...)`
- Assert envelope metadata and body/error contract, not just HTTP status
- Include at least one negative-path assertion with expected domain error code

## Coverage Baseline (Mandatory)

Acceptance-criteria coverage is the minimum floor, not the target ceiling.
In addition to at least one test per AC, include this baseline matrix for each feature:

- Validation boundary coverage: empty list, null field, max/min boundary, or format mismatch as applicable
- Unhappy-path business flow: at least one service or handler negative path that returns a controlled error
- Downstream failure mapping (when external calls exist): timeout, 5xx, and any retry-exhausted behavior mapped to expected app error
- Success path coverage: at least one representative happy path with full envelope assertions

If a baseline category is not applicable, add an explicit note in the test plan or PR summary stating why it is N/A.

---

## Part A — Handler Integration Test

Handler tests exercise the full request lifecycle: routing → filter → validator → handler → service → DB + downstream stubs.


**Rules:**
- Always annotate with `@VerticalSliceTest` — this provides Spring context, embedded Redis, WireMock connector, WebTestClient, and `test` profile
- Always extend `MainTest` — provides WireMock stub helpers, endpoint path constants, and the shared per-test lifecycle methods
- **Never add `@DirtiesContext`** — see "Context Caching" section below
- **Never add `@MockitoBean` or `@MockBean`** in integration test classes — see "Context Caching" section below
- Inject `WebTestClient` — auto-configured with 10s timeout

### Step 2 — Test-specific DB state (`@BeforeEach`)

`MainTest.resetDatabase()` re-runs `data.sql` (DELETE + INSERT seed rows) before every test, so the DB always starts from a known clean state. If a test needs rows beyond the seed data, add them in a `@BeforeEach` method **in the subclass** — it runs after `MainTest.resetDatabase()`:

```java
@BeforeEach
void seedOrderForTest() {
    db.sql("INSERT INTO _promo (id, promo_code, ...) VALUES (...)"
    ).fetch().rowsUpdated().block();
}
```

Do **not** use `@DirtiesContext` to achieve isolation — it restarts the entire Spring context which costs ~7–10 s per test class.

### Step 3 — Add endpoint path constants to MainTest

If the endpoint under test is not already in `MainTest`, add it:

```java
// In MainTest.java
public static final String _{FEATURE} = _BASE_PATH + "/{feature-path}";
```

### Step 4 — Register dynamic properties for new downstream services

If the feature calls a new downstream service not already registered, add it to `MainTest.registerDynamicProperties()`:

```java
@DynamicPropertySource
static void registerDynamicProperties(DynamicPropertyRegistry registry) {
    // existing registrations...
    registry.add("service.{new-service}.base-url", wireMock::baseUrl);
}
```

### Step 5 — Stub downstream APIs

Create a helper method that sets up the happy-path stubs:

```java
private void stubDownstreamHappyPath() {
    getResponseWithJson(CATALOGUE_PRODUCTS_BY_SKU, "catalogue-api-response-single.json", OK);
    postResponseWithJson(VENDOR_MANAGE_VENDORS, "vendor-api-response-single.json", OK);
}
```

**Available stub helpers from MainTest:**
- `getResponseWithJson(path, file, status)` — GET stub
- `getResponseWithJson(path, queryParams, file, status)` — GET with query params
- `postResponseWithJson(path, file, status)` — POST stub
- `putResponseWithJson(path, file, status)` — PUT stub
- `patchResponseWithJson(path, file, status)` — PATCH stub
- `postResponseWithError(path, status, errorBody)` — POST error response
- `getResponseWithError(path, status, errorBody)` — GET error response

Stubs registered in a test method are automatically cleared after the test by `MainTest.resetWireMock()` — no manual cleanup needed.

### Step 6 — Write test methods

```java
@Test
void should_{expectedBehavior}_when_{condition}() {
    stubDownstreamHappyPath();

    String requestBody = """
        {
          "field": "value"
        }
        """;

    client
        .post()
        .uri(_{FEATURE})
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(requestBody)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.body.result.fieldName").isEqualTo("expectedValue")
        .jsonPath("$.body.result.status").isNotEmpty()
        .jsonPath("$.body.message").doesNotExist();
}
```

**Test naming convention:** `should_{outcome}_when_{condition}`

**Response assertion pattern for Response envelope:**
- Success: `$.body.result.*` contains the data
- Error: `$.body.errorCode` and `$.body.message` contain error details
- `$.body.referenceId` is always present

**For GET endpoints:**
```java
client
    .get()
    .uri(_{FEATURE} + "/{id}", actualId)
    .exchange()
    .expectStatus().isOk()
    .expectBody()
    .jsonPath("$.body.result.id").isEqualTo(actualId);
```

### Step 6 — Create WireMock stub data files

Place JSON response files in `src/test/resources/__files/`:

```
src/test/resources/__files/{service}-api-response.json
src/test/resources/__files/{service}-api-response-single.json
```

The file content should match the real downstream API's response shape. Derive from the task spec or API documentation.

---

## Context Caching — Architecture and Violations

All `@VerticalSliceTest` classes share **one** Spring `ApplicationContext` for the entire test JVM run. This is possible because they all have the same context cache key (`@SpringBootTest` class, `@ActiveProfiles`, `@DynamicPropertySource` values). The shared context starts once in ~7–10 s and is reused by every test class.

State isolation between test methods is provided by `MainTest`:
- **`@AfterEach resetWireMock()`** — resets all WireMock stubs on port 9893 (downstream APIs) and port 9894 (msg-platform) after every test
- **`@BeforeEach resetDatabase()`** — re-runs `data.sql` before every test to restore the seed state

### NEVER do these — they break context sharing and slow the suite

| Violation | Why it breaks caching | Cost |
|---|---|---|
| `@DirtiesContext` on any test class | Evicts the cached context, forces full restart for next class | +7–10 s per class × N classes |
| `@MockitoBean` / `@SpyBean` in a class | Changes the context cache key; that class always gets its own context | +7–10 s extra per class |
| `@MockBean` (deprecated) | Same as `@MockitoBean` | Same |
| Different `@ActiveProfiles` per class | Different cache key — new context | Same |

> If you genuinely need a mock for a specific bean, prefer replacing its downstream call with a WireMock stub instead of `@MockitoBean`. The mock should only be the last resort when the interaction cannot be expressed as HTTP.

---

## Part B — Service Unit Test

Service tests isolate business logic by mocking all dependencies.

### Step 1 — Create the test class

```java
package com.acme.digital..{feature}.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class {Feature}ServiceTest {

    @Mock private {Repository} repository;
    @Mock private {Client} client;

    @InjectMocks
    private {Feature}Service service;
}
```

### Step 2 — Mock reactive return values

```java
@BeforeEach
void setUp() {
    when(repository.findById(anyLong()))
        .thenReturn(Mono.just(testEntity));
    when(repository.save(any({Entity}.class)))
        .thenReturn(Mono.just(savedEntity));
}
```

**Rules:**
- Mock `Mono.just()` for found results, `Mono.empty()` for not-found
- Mock `Flux.fromIterable()` for collection results
- Mock `Mono.error()` for error scenarios
- When mocking `TransactionalOperator.transactional()`, pass through the argument:
  ```java
  when(transactionalOperator.transactional(any(Mono.class)))
      .thenAnswer(inv -> inv.getArgument(0));
  ```

### Step 3 — Assert with StepVerifier

```java
@Test
void should_{outcome}_when_{condition}() {
    StepVerifier.create(service.{method}(args))
        .assertNext(result -> {
            assertThat(result.field()).isEqualTo(expected);
        })
        .verifyComplete();
}
```

**StepVerifier patterns:**

```java
// Expect single value then complete
StepVerifier.create(mono)
    .assertNext(item -> assertThat(item).isNotNull())
    .verifyComplete();

// Expect error
StepVerifier.create(mono)
    .expectErrorMatches(ex ->
        ex instanceof OrderNotFoundException
        && ex.getMessage().contains("not found"))
    .verify();

// Expect empty (Mono.empty())
StepVerifier.create(mono)
    .verifyComplete();  // completes without emitting

// Expect multiple items from Flux
StepVerifier.create(flux)
    .expectNextCount(3)
    .verifyComplete();

// Verify mock interactions
verify(repository).save(any());
verify(repository, never()).deleteById(anyLong());
```

---

## Part C — Client WireMock Test

For testing WebClient-based clients in isolation without the full Spring context.

### Step 1 — Create the test class

```java
package com.acme.digital..{feature}.client;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

@WireMockTest
class {Name}ClientTest {

    private {Name}Client client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wm) {
        WebClient webClient = WebClient.builder().build();
        String baseUrl = "http://localhost:" + wm.getHttpPort();
        {Name}Properties properties = new {Name}Properties(
            baseUrl, "test-key", 0L, 5000L);
        client = new {Name}Client(webClient, properties);
    }
}
```

### Step 2 — Register stubs inline

```java
@Test
void should_returnResponse_when_downstreamReturns200(WireMockRuntimeInfo wm) {
    wm.getWireMock().register(
        get(urlPathEqualTo("/api/endpoint"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBodyFile("{service}-api-response.json")));

    StepVerifier.create(client.getItems())
        .assertNext(items -> assertThat(items).hasSize(2))
        .verifyComplete();
}
```

---

## Part D — Validator Test

Validator tests are plain JUnit 5 — no Spring context, no mocks.

```java
package com.acme.digital..{feature}.validator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class {Feature}ValidatorTest {

    private {Feature}Validator validator;

    @BeforeEach
    void setUp() {
        validator = new {Feature}Validator();
    }

    @Test
    void should_passValidation_when_allFieldsValid() {
        var request = buildRequest(/* valid data */);
        var errors = validator.validate(request);
        assertThat(errors).isEmpty();
    }

    @Test
    void should_failValidation_when_fieldExceedsMaxLength() {
        var request = buildRequest(/* invalid data */);
        var errors = validator.validate(request);
        assertThat(errors).anyMatch(e -> e.getField().contains("fieldName"));
    }
}
```

---

## Test Configuration Files

### application-test.yaml

Located at `src/test/resources/application-test.yaml`. When adding a new downstream service, add its placeholder config:

```yaml
service:
  {new-service}:
    base-url: http://localhost:9999  # overridden at runtime via @DynamicPropertySource
    api-key: test-key
    retry-count: 0     # disable retries in tests for speed
    timeout-ms: 5000
```

Set `retry-count: 0` in test profile to avoid slow test execution from retries.

### Embedded Redis — Use a Random Port

Do not hardcode embedded Redis to port `6379`. If that port is already in use, tests silently connect to whatever Redis is running (potentially a non-empty instance with stale data, causing flaky tests). Use a random available port and wire it via `@DynamicPropertySource`:

```java
private static final int REDIS_PORT = SocketUtils.findAvailableTcpPort();

@DynamicPropertySource
static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.port", () -> REDIS_PORT);
}
```

### Handler Assertion Quality

Prefer `jsonPath` assertions over `toString().contains()`. String-contains checks match any occurrence anywhere in the serialized JSON and break when unrelated fields happen to contain the same substring:

```java
// Fragile — matches anywhere in the JSON string
assertThat(response.toString()).contains("CREATED");

// Precise — asserts the exact field value
.jsonPath("$.body.result.status").isEqualTo("CREATED")
.jsonPath("$.body.errorCode").isEqualTo("ORDER_NOT_FOUND")
```

When testing `TransactionResponse` objects in service/unit tests where `jsonPath` is unavailable, extract the `Response` body and assert individual fields rather than the serialized string.

### Test Gap Awareness

When writing tests for a new feature, check that these scenarios are covered (in addition to the baseline matrix above):

- **Partial DB write failure** — if the service writes to multiple tables, verify `@Transactional` rollback behavior by simulating a failure mid-chain
- **Downstream infrastructure failure** — timeout/5xx from external services mapped to the correct domain error code
- **Exception handler assertions** — verify the HTTP status, error code, AND message content (not just `response != null`)

### schema.sql

Located at `src/test/resources/schema.sql`. When adding new tables, add H2-compatible DDL:
- Use `BIGINT AUTO_INCREMENT PRIMARY KEY` for IDs
- Use `CLOB` instead of `JSONB` (H2 has no native JSONB)
- Use `DECIMAL(12,2)` for monetary amounts
- Use `TIMESTAMP` for date/time fields
- Always include `IF NOT EXISTS`

### WireMock stub files

Located at `src/test/resources/__files/`. Naming convention:
```
{service}-api-response.json          # multi-item response
{service}-api-response-single.json   # single-item response
{service}-api-response-{variant}.json # edge case variants (e.g., oos, with-promo)
```

---

## Checklist

- [ ]  integration test class annotated with `@VerticalSliceTest` (no `@DirtiesContext`)
- [ ] Test class extends `MainTest` for WireMock helpers
- [ ] Endpoint path constants added to `MainTest` if new
- [ ] Downstream service properties registered in `@DynamicPropertySource` if new
- [ ] WireMock stub JSON files created in `src/test/resources/__files/`
- [ ] Service unit tests use `@ExtendWith(MockitoExtension.class)` + `StepVerifier`
- [ ] All reactive assertions use `StepVerifier` — no `.block()` in tests
- [ ] Test names follow `should_{outcome}_when_{condition}` convention
- [ ] At least one test per acceptance criterion is implemented
- [ ] Baseline validation boundary test is implemented (or marked N/A with reason)
- [ ] Baseline unhappy-path service/handler test is implemented
- [ ] Baseline downstream failure-mapping tests (timeout/5xx) are implemented when external calls exist
- [ ] Baseline happy-path envelope assertions are implemented
- [ ] New tables added to `src/test/resources/schema.sql` with H2-compatible DDL
- [ ] Test config for new services added to `application-test.yaml` with `retry-count: 0`
