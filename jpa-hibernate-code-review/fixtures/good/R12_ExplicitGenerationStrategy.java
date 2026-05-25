// FIXTURE: R12 — clean implementation
// This file demonstrates the CORRECT pattern for rule R12.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R12.
package com.example.fixtures.good;

import jakarta.persistence.*;

@Entity
public class Account {

    // CORRECT: strategy is explicit. No reliance on GenerationType.AUTO which
    // picks different defaults across Hibernate versions and dialects.
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "account_seq")
    @SequenceGenerator(
        name = "account_seq",
        sequenceName = "account_seq",
        allocationSize = 50
    )
    private Long id;

    private String iban;
    private long balanceCents;

    public Long getId() { return id; }
}
