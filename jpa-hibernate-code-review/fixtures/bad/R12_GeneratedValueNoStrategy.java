// FIXTURE: R12 — @GeneratedValue without explicit strategy on Postgres/Oracle
// Expected severity: MEDIUM
// Expected finding: @GeneratedValue with no strategy defaults to AUTO, behavior varies across Hibernate versions

package com.example.fixtures.bad;

import jakarta.persistence.*;

// application.properties: spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
@Entity
public class Account {

    @Id
    @GeneratedValue
    private Long id;

    private String email;
    private String name;

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
