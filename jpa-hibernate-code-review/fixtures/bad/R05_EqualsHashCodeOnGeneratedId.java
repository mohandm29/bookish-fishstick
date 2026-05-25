// FIXTURE: R05 — equals / hashCode on @Entity using mutable fields or auto-generated @Id
// Expected severity: HIGH
// Expected finding: equals/hashCode reference the auto-generated @Id which is null pre-persist and mutates afterward

package com.example.fixtures.bad;

import jakarta.persistence.*;
import java.util.Objects;

@Entity
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sku;
    private String name;

    public Long getId() { return id; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Product)) return false;
        Product other = (Product) o;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
