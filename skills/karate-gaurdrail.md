---
name: karate-test-authoring-guardrails
description: >
  Create or refactor Karate API tests from testcase/scenario input in online-rrp-buyflow.
  Use when generating drift-aware feature files, deciding feature placement by endpoint domain,
  and validating through the unified full-suite Karate flow with actionable triage.
---

# Karate Test Authoring Guardrails

## When to Use
- Converting testcase docs or scenario lists into Karate `.feature` files
- Refactoring flaky or stale Karate assertions to match current source behavior
- Organizing new feature files under the correct domain folder
- Running live verification and triaging suite failures

## Core Rules

1. Source code is the contract authority
- Align assertions to handlers, services, validators, and route config.
- If generated testcase docs differ from source behavior, follow source behavior and document drift in scenario naming/comments.

2. Keep one execution path
- Use the existing unified runner/task flow only.
- Do not create a new Karate runner class or extra Gradle test variant for testcase features.

3. Always validate with the full suite
- Live verification must run the full unified suite with `./gradlew karateSmoke`.
- Do not treat targeted single-feature execution as final verification.

4. Infer subfolder from endpoint domain
- Place feature files under `src/test/java/karate/<domain>/` where `<domain>` is inferred from endpoint path and existing folder patterns.
- Fallback to `src/test/java/karate/order/` when inference is unclear.

5. Preserve existing framework patterns
- Reuse existing helpers, session/cookie handling, and response-envelope assertions.
- Keep assertions strict, but avoid brittle assumptions not guaranteed by source behavior.

6. Readme update policy
- Do not update `src/test/java/karate/order/api-testcases-readme.md` unless explicitly requested.

## Live Verification and Triage Standard

1. Start app locally (local profile).
2. Wait for readiness.
3. Run `./gradlew karateSmoke`.
4. If failing, triage using both:
- Karate report artifacts (`build/karate-reports/`)
- Server logs from the same run window
5. Classify root cause as one of:
- Assertion drift
- Request setup/session issue
- Downstream dependency/unavailable environment
- Application code defect
- Data/setup fixture gap

## Recommended Triage Output
- Failing scenario and feature path
- Request/response mismatch summary
- Relevant server log lines (endpoint, error code, exception)
- Probable root cause category
- Minimal fix recommendation
- Re-run command and expected confirmation signal
