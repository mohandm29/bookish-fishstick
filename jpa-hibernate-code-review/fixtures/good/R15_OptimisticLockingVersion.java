// FIXTURE: R15 — clean implementation
// This file demonstrates the CORRECT pattern for rule R15.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R15.
package com.example.fixtures.good;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "product_seq")
    @SequenceGenerator(name = "product_seq", sequenceName = "product_seq", allocationSize = 50)
    private Long id;

    // CORRECT: @Version enables optimistic concurrency. Concurrent updates that
    // overwrite each other now fail fast with OptimisticLockException instead of
    // silently losing data.
    @Version
    private Long version;

    private String name;
    private BigDecimal price;

    public Long getId() { return id; }
    public Long getVersion() { return version; }
}
