// FIXTURE: R15 — Mutable entity used across HTTP requests with no @Version
// Expected severity: MEDIUM
// Expected finding: Entity mutated across CRUD endpoints with no @Version field; concurrent edits cause silent lost updates

package com.example.fixtures.bad;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    private String reference;

    private String status;

    private BigDecimal total;

    public Long getId() { return id; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
}
