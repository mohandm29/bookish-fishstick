---
name: code-review-expert
description: >
  Perform a critical post-implementation code review as a senior engineer.
  Use when: the user asks to "review the code", "do a critical review", "review this implementation",
  or requests a post-execute-plan quality audit focused on defects, regressions, risks, and missing tests.
argument-hint: '<optional scope: feature path, module, task file, or changed files>'
---

# Critical Code Review (Post-Implementation)

## Purpose

- Find real defects and behavioral regressions before merge.
- Identify architectural risks, resilience gaps, and maintainability issues.
- Verify test adequacy for both acceptance criteria and failure paths.

## When to Use

- User explicitly asks for review after implementation.
- After `execute-plan` completes and user wants a critical audit.
- Before creating PR/merge for a high-impact feature.

## Inputs

- Optional review scope (module, path, task file, or changed files).
- If no scope is provided, review all staged/unstaged feature changes.

## Review Workflow

1. Determine review scope
- Prefer changed files for focused review (`git diff` / changed files).
- Expand scope only where dependencies indicate hidden impact.

2. Prioritize risk areas
- Contract and behavior changes (handlers, services, DTOs, mappers)
- Error mapping and retry/timeout handling
- Session/ownership/security boundaries
- Data consistency, idempotency, and state transitions
- Cross-endpoint duplication and abstraction drift

3. Validate test posture
- Confirm tests cover ACs.
- Confirm baseline non-AC coverage: validation boundaries, downstream failures, unhappy paths.
- Flag missing tests as findings when risk is non-trivial.

4. Assess production quality
- Logging/PII safety
- Config/default safety
- Backward compatibility and migration risk
- Performance hotspots (N+1 calls, blocking operations, unnecessary fan-out)

5. Summarize findings
- Findings first, ordered by severity.
- Include file references and why each issue matters.

## Severity Levels

- **High**: likely production defect, data loss/corruption risk, auth/security violation, or clear behavioral regression.
- **Medium**: meaningful reliability/maintainability risk or missing failure-path coverage.
- **Low**: minor correctness/readability issues with limited blast radius.

## Output Format

```markdown
## Code Review Findings

### High
1. [path/to/file.ext](path/to/file.ext#L123): Issue summary and impact.

### Medium
1. [path/to/file.ext](path/to/file.ext#L45): Issue summary and impact.

### Low
1. [path/to/file.ext](path/to/file.ext#L88): Issue summary and impact.

### Open Questions
1. Clarifications needed from product/architecture perspective.

### Residual Risks / Test Gaps
1. Remaining risks if merged as-is.

### Change Summary
Short secondary summary only after findings.
```

## Constraints

- Findings must be evidence-based and actionable.
- Prefer concrete issues over style-only comments.
- If no findings exist, state that explicitly and still report residual risks/test gaps.
- Do not rewrite implementation unless user asks for fixes.
