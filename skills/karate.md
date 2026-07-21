---
name: karate-smoke-test
description: >
  Generate Karate DSL smoke-test feature files and run them against a live server to validate
  API endpoints end-to-end. Use when: the implementation plan includes new or modified API
  endpoints that need live verification beyond unit/integration tests.
---

# Karate API Smoke Tests

## When to Use
- The plan introduces new API endpoints (handlers + routes)
- The plan modifies existing endpoint behaviour (new fields, status codes, error responses)
- The story has multi-step flows where endpoints depend on each other (e.g., create → add items → checkout)

Do NOT use when the plan only changes internal logic (services, mappers, DB queries) with no API contract changes.

## Prerequisites

The project has Karate DSL pre-configured:

| Component | Location |
|---|---|
| Dependency | `io.karatelabs:karate-core:2.0.8` in `build.gradle.kts` |
| Global config | `src/test/java/karate-config.js` — provides `baseUrl` and `sessionCookie` variables |
| JUnit5 runner | `src/test/java/karate/KarateSmokeRunner.java` — runs all `@smoke`-tagged features |
| Gradle task | `./gradlew karateSmoke` — excluded from normal `./gradlew test` |
| Existing features | `src/test/java/karate/orders/orders-smoke.feature` — reference example |

## Procedure

### Step 1 — Map the call dependency chain

Read the plan's ACs and API contract. Identify which endpoints must be called first to set up state. Order them as a numbered sequence:

```
# Example: Shopping Cart story
# 1. POST /orders              → creates order, returns masterOrderId
# 2. POST /orders/{id}/items   → adds item, requires masterOrderId from step 1
# 3. GET  /orders/{id}         → retrieves order with items from steps 1+2
# 4. DELETE /orders/{id}/items/{itemId} → removes item, requires itemId from step 3
# 5. POST  /orders/{id}/cancel  → cancels order
```

### Step 2 — Generate the Karate feature file

Create `src/test/java/karate/{feature}/{feature-name}-smoke.feature`:

```gherkin
@smoke
Feature: {Feature Name} — End-to-End Smoke Test

  Background:
    * url baseUrl
    * header Cookie = sessionCookie

  Scenario: {Story summary — full happy-path flow}
    # ── Step 1: Create order ─────────────────────────────────────────────
    Given path '//orders'
    And request { items: [{ sku: '320013420N', productId: '8028036', quantity: 1 }] }
    When method POST
    Then status 200
    And match response.body.result.masterOrderId == '#string'
    And match response.body.result.status == 'CREATED'
    * def orderId = response.body.result.masterOrderId

    # ── Step 2: Get order (depends on Step 1) ────────────────────────────
    Given path '//orders/' + orderId
    When method GET
    Then status 200
    And match response.body.result.masterOrderId == orderId
    And match response.body.result.items == '#[1]'

  Scenario: Validation error — empty items
    Given path '//orders'
    And request { items: [] }
    When method POST
    Then status 200
    And match response.body.errorCode == '#string'
```

### Feature file rules

- Tag with `@smoke` — the runner filters on this tag
- Use `Background` to set `url baseUrl` and `header Cookie = sessionCookie` (from `karate-config.js`)
- Chain dependent calls within a single `Scenario` — Karate preserves variable state across steps
- Use `* def varName = response.body.path` to extract values for subsequent requests
- Use `match` with type markers: `#string`, `#number`, `#[N]` (array of size N), `#notnull`
- Include at least one error scenario (validation failure, not-found)
- One feature file per story — multiple scenarios within it
- For reusable setup (e.g., "create an order"), extract into a separate `.feature` and call it:
  ```gherkin
  * def result = call read('classpath:karate/orders/create-order.feature')
  * def orderId = result.orderId
  ```

### Step 3 — Start the application

```bash
./gradlew bootRun --args='--spring.profiles.active=local' &
```

Wait for readiness (up to 30 seconds). The local profile runs on port 8011:
```bash
for i in $(seq 1 30); do
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8011/actuator/health 2>/dev/null | grep -q 200 && break || sleep 1
done
```

### Step 4 — Run Karate smoke tests

```bash
./gradlew karateSmoke -Dkarate.env=local
```

This executes all `@smoke`-tagged features under `src/test/java/karate/` and produces an HTML report at `build/karate-reports/`.

### Step 5 — Shutdown

Stop the Gradle bootRun process (not by port — by process name to avoid killing unrelated services):
```bash
pkill -f 'bootRun' 2>/dev/null || true
```

**If startup fails** (e.g., database or Redis not available locally), skip the smoke test, note it in the completion report, and proceed. The unit/integration tests are the primary verification gate.

## Checklist

- [ ] Call dependency chain mapped from plan's API contract
- [ ] Feature file created at `src/test/java/karate/{feature}/{feature-name}-smoke.feature`
- [ ] All new endpoints covered with at least one happy-path assertion
- [ ] At least one error scenario included (validation, not-found)
- [ ] Dependent calls chained within a single Scenario using `* def`
- [ ] Feature tagged with `@smoke`
- [ ] `./gradlew karateSmoke -Dkarate.env=local` passes (or skipped with note)
