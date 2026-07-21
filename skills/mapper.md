---
name: mapstruct-mapper
description: >
  Create or refactor mapper classes using MapStruct correctly in this project.
  Use when creating/updating `*Mapper.java`, replacing manual mapping logic,
  defining multi-source mappings, or fixing MapStruct anti-patterns.
---

# MapStruct Mapper Skill

## Source of Truth
Use the official MapStruct reference as the baseline:
- https://mapstruct.org/documentation/stable/reference/html/

Most relevant sections:
- 3.1 Basic mappings
- 3.3 Adding custom methods to mappers
- 3.4 Mapping methods with several source parameters
- 5.2 Mapping object references
- 11.1 Mapping configuration inheritance

## Core Principle
Use MapStruct to generate mappings by declaring abstract mapping methods.
Do not hand-write constructor mapping in `default` methods unless logic cannot be expressed with MapStruct annotations.

## When to Use
- Creating a new mapper interface
- Refactoring a mapper that manually constructs DTOs
- Adding fields to source/target types and keeping mapper in sync
- Implementing multi-source mappings (e.g., `entity + computed values -> dto`)

## Mandatory Rules

1. Prefer generated mapping methods over manual mapping
- Good:
```java
@Mapper(componentModel = "spring")
public interface OrderMapper {
  @Mapping(target = "status", source = "record.orderStatus")
  OrderDto toOrderDto(RrpOrderRecord record, List<OrderItemDto> items);
}
```
- Avoid:
```java
default OrderDto toOrderDto(RrpOrderRecord record, List<OrderItemDto> items) {
  return new OrderDto(record.getId(), record.getOrderStatus(), record.getVendor(), items);
}
```

2. Use `@Mapping` only when names differ or source is ambiguous
- Same-name fields map automatically.
- Explicitly qualify multi-source fields (`source = "record.orderStatus"`).

3. Keep `default` methods only for non-trivial logic
- Allowed only when logic cannot be represented with `@Mapping` / nested mapper methods.
- If a `default` method is just a constructor call, replace it with an abstract mapping method.

4. Keep business logic out of mappers
- Mappers transform structures only.
- Validation, orchestration, state transitions, and DB decisions belong in services.

5. Preserve Spring DI usage
- Use `@Mapper(componentModel = "spring")`.

6. Keep conversion helpers declarative when possible
- Prefer MapStruct-supported implicit conversions (e.g., UUID -> String, numeric conversions).
- If a special conversion is required, declare dedicated mapping methods MapStruct can call.

7. Be explicit for multi-source methods
- For mapper methods with 2+ parameters, always specify `source = "param.field"` for renamed/ambiguous targets.

8. Add/maintain mapper unit tests
- Ensure mapper tests cover renamed fields, null handling, and multi-source methods.

## Recommended Mapper Template

```java
@Mapper(componentModel = "spring")
public interface ExampleMapper {

  @Mapping(target = "status", source = "entity.orderStatus")
  TargetDto toTarget(Entity entity, List<ItemDto> items);

  @Mapping(target = "id", source = "entity.id")
  ItemDto toItemDto(EntityItem entity, Integer quantity);
}
```

## Refactor Workflow

1. Inspect existing mapper and mark manual constructor methods.
2. Convert each constructor-only `default` method into an abstract method.
3. Add `@Mapping` only where needed (renamed/ambiguous fields).
4. Keep only truly custom `default` methods.
5. Run compile and mapper tests.
6. Ensure no behavior regressions in service/handler tests.

## Done Checklist
- [ ] No constructor-only `default` mapping methods remain
- [ ] All renamed fields declared via `@Mapping`
- [ ] Multi-source methods use parameter-qualified sources
- [ ] Mapper compiles with generated implementation
- [ ] Mapper tests pass
