---
name: jpa-hibernate-code-review
description: Review Java / Spring / JPA / Hibernate code for persistence anti-patterns — N+1 queries, cascade misuse, eager fetching, identifier strategy errors, locking gaps, transaction boundary problems, batch inefficiencies, OSIV abuse, and equals/hashCode bugs. Use when the user asks to review, audit, lint, or check a JPA/Spring codebase, when finishing a feature touching @Entity / @Repository / @Transactional, or before opening a PR that changes persistence code. Triggers on: "review my JPA code", "check this entity", "audit persistence layer", or after scanning a directory with @Entity files.
---

# JPA / Hibernate Code Review

Apply this skill when reviewing Java/Spring/JPA/Hibernate code. Walk the rule checklist, emit findings in the specified format, and cite the source article slug for each.

## Workflow

1. **Enumerate scope.** Find `.java` files containing any of: `@Entity`, `@MappedSuperclass`, `@Embeddable`, `@Repository`, `@Transactional`, `EntityManager`, `Session`, `SessionFactory`, `@Query`, `JpaRepository`. Also scan `application.properties` / `application.yml` / `persistence.xml` for persistence config.
2. **Walk the checklist** in `references/critical-rules.md` for each file. Each rule has: detection pattern, severity, why it matters, source article.
3. **For ambiguous findings**, load the matching topic reference from `jpa-hibernate-best-practices/references/<topic>.md` for the full rationale before reporting.
4. **Render findings** using the **Output format** below. One block per finding. Quote the actual code with file path + line number.
5. **Aggregate** with the summary table at the end (count per severity).
6. **Suggest fixes**: pull canonical replacements from `references/fix-recipes.md`.

Do not block or auto-fix. Findings are advisory — the reviewer (human) decides what to merge.

## Severity legend

| Severity | Meaning | Action |
| --- | --- | --- |
| **CRITICAL** | Data loss, deletion of unrelated rows, lost updates, or correctness bug in production traffic | Block merge until fixed |
| **HIGH** | Performance regression visible under load (N+1, Cartesian, in-memory pagination, batch disabled) or violates persistence-context lifecycle | Fix before merge |
| **MEDIUM** | Suboptimal but not always wrong; depends on context | Discuss with author |
| **LOW** | Style / hygiene; safe to defer | Note in review, optional |

## Output format

For every finding, emit exactly:

```
## Finding: <one-line title>
- **Severity**: CRITICAL | HIGH | MEDIUM | LOW
- **File**: path/to/File.java:line
- **Rule**: <rule id from critical-rules.md>
- **Quote**:
  ```java
  <exact code, 1-5 lines>
  ```
- **Why this matters**: <2-3 sentences, end with citation like [eager-fetching-is-a-code-smell.md]>
- **Fix**:
  ```java
  <corrected code from fix-recipes.md or adapted>
  ```
```

After all findings, emit a summary table:

```
## Review Summary

| Severity | Count |
| --- | --- |
| CRITICAL | N |
| HIGH | N |
| MEDIUM | N |
| LOW | N |

**Files reviewed**: M
**Total findings**: N

### Top priorities
1. <one-line summary of most critical finding>
2. ...
```

## Rule checklist (quick view)

The full table — detection pattern, rationale, severity, source — is in `references/critical-rules.md`. Load that file at the start of any review. Quick summary of 20 rules:

### Associations & Cascade
- **R01** `@ManyToMany(cascade = CascadeType.ALL | CascadeType.REMOVE)` → **CRITICAL** (deletes unrelated rows)
- **R02** `@OneToMany(cascade = CascadeType.ALL)` without `mappedBy` → **HIGH** (extra join table, broken parent-child semantics)
- **R03** Bidirectional association without `addX`/`removeX` sync helpers → **HIGH** (stale collections, lost cascades)
- **R04** `@OneToMany` declared on `List` for a `@ManyToMany`-like relationship → **MEDIUM** (Hibernate full-delete bug on update)
- **R05** `@Entity` overriding `equals`/`hashCode` with mutable fields or auto-generated `@Id` → **HIGH** (Set membership breaks)

### Fetching
- **R06** `@OneToMany` or `@ManyToMany` with `fetch = FetchType.EAGER` → **HIGH** (Cartesian product, unbounded reads)
- **R07** `@ManyToOne` / `@OneToOne` without explicit `fetch = FetchType.LAZY` → **MEDIUM** (silent eager default)
- **R08** `@LazyCollection(LazyCollectionOption.EXTRA)` → **HIGH** (per-access query, N+1)
- **R09** JPQL `JOIN FETCH` of two unrelated `*ToMany` collections → **CRITICAL** (Cartesian product)
- **R10** `Pageable` combined with `JOIN FETCH` on a `*ToMany` → **HIGH** (HHH000104, in-memory pagination)

### Identifiers & Batching
- **R11** `GenerationType.IDENTITY` in a class that participates in bulk inserts → **HIGH** (JDBC batching disabled)
- **R12** `@GeneratedValue` without explicit `strategy` on Postgres/Oracle → **MEDIUM** (defaults to AUTO → TABLE/SEQUENCE depending on version)
- **R13** Loop with `entityManager.persist()` / `repository.save()` > 5 iterations without `flush()/clear()` or `saveAll(batch)` cadence → **HIGH** (heap pressure, batch broken)
- **R14** Entity-by-entity delete loop where bulk `@Modifying @Query` would do → **MEDIUM**

### Concurrency & Transactions
- **R15** Mutable entity used across HTTP requests with no `@Version` → **MEDIUM** (lost updates invisible)
- **R16** `find()` / `get()` in a write path without `LockModeType` → **MEDIUM** (race window)
- **R17** `@Transactional` on a read-only method without `readOnly = true` → **LOW** (loses 50% memory savings + replica routing)
- **R18** Self-invocation of `@Transactional` method (`this.otherTx()`) → **HIGH** (proxy bypass — no transaction)

### Config & Misc
- **R19** `spring.jpa.open-in-view=true` (default in Spring Boot) → **HIGH** (OSIV anti-pattern, N+1 risk in view layer)
- **R20** `hibernate.enable_lazy_load_no_trans=true` → **HIGH** (anti-pattern — opens a new tx per lazy load)

Full pattern signatures, source article citations, and edge-case notes live in `references/critical-rules.md`. Canonical fixes are in `references/fix-recipes.md`.

## Decision: when to consult `jpa-hibernate-best-practices`

If a finding warrants a deeper explanation than fits in the WHY field — e.g., explaining why `Pageable + JOIN FETCH` triggers in-memory pagination, or how `@MapsId` solves the `@OneToOne` eager-fetch problem — load the matching topic reference from `jpa-hibernate-best-practices/references/<topic>.md` and quote one or two lines.

## Anti-patterns the reviewer should AVOID

- Reporting `@ManyToOne(fetch = EAGER)` as CRITICAL — it's MEDIUM at most; sometimes intentional for required fields.
- Demanding `@Version` on every entity — only when used across requests.
- Flagging `open-in-view = true` without checking whether it's an explicit choice (some teams accept the perf cost for simpler controllers).
- Marking style issues as HIGH.

## Test the skill against the fixtures

This skill ships with a small fixture suite:

- `fixtures/bad/` — one Java file per rule, with the violation seeded and a comment naming the expected finding ID.
- `fixtures/good/` — clean reference implementations.

When iterating on this skill, run:
> "Review the JPA code at `fixtures/bad/`." — expect 1+ finding per file, IDs matching the seeded comments.
> "Review the JPA code at `fixtures/good/`." — expect 0 CRITICAL / HIGH findings.
