// FIXTURE: R11 — GenerationType.IDENTITY in a class participating in bulk inserts
// Expected severity: HIGH
// Expected finding: GenerationType.IDENTITY disables JDBC batch inserts for bulk-loaded entities

package com.example.fixtures.bad;

import jakarta.persistence.*;
import java.util.List;

@Entity
class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reference;
    private String customer;

    public Long getId() { return id; }
    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }
    public String getCustomer() { return customer; }
    public void setCustomer(String customer) { this.customer = customer; }
}

class OrderBatchImporter {

    @PersistenceContext
    private EntityManager em;

    public void importAll(List<Order> orders) {
        for (Order o : orders) {
            em.persist(o);
        }
    }
}
