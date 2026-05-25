// FIXTURE: R11 — clean implementation
// This file demonstrates the CORRECT pattern for rule R11.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R11.
package com.example.fixtures.good;

import jakarta.persistence.*;

@Entity
@Table(name = "orders")
public class Order {

    // CORRECT: SEQUENCE allows Hibernate's JDBC batching to work. IDENTITY would
    // disable batch inserts because the DB must round-trip per row to return the PK.
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
    @SequenceGenerator(name = "order_seq", sequenceName = "order_seq", allocationSize = 50)
    private Long id;

    private String reference;

    public Long getId() { return id; }
}

// application.properties:
//   spring.jpa.properties.hibernate.jdbc.batch_size=50
//   spring.jpa.properties.hibernate.order_inserts=true
//   spring.jpa.properties.hibernate.order_updates=true
