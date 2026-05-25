// FIXTURE: R05 — clean implementation
// This file demonstrates the CORRECT pattern for rule R05.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R05.
package com.example.fixtures.good;

import jakarta.persistence.*;
import org.hibernate.annotations.NaturalId;
import java.util.Objects;

@Entity
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "product_seq")
    @SequenceGenerator(name = "product_seq", sequenceName = "product_seq", allocationSize = 50)
    private Long id;

    // CORRECT: a stable business key set at creation and never updated.
    @NaturalId
    @Column(nullable = false, unique = true, updatable = false)
    private String sku;

    private String name;

    protected Product() {}
    public Product(String sku, String name) { this.sku = sku; this.name = name; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Product that)) return false;
        return Objects.equals(sku, that.sku);
    }

    @Override
    public int hashCode() {
        // Constant-safe hash based on the immutable business key, not on the generated id.
        return Objects.hash(sku);
    }
}
