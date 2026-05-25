// FIXTURE: R07 — clean implementation
// This file demonstrates the CORRECT pattern for rule R07.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R07.
package com.example.fixtures.good;

import jakarta.persistence.*;

@Entity
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post_seq")
    @SequenceGenerator(name = "post_seq", sequenceName = "post_seq", allocationSize = 50)
    private Long id;

    private String title;

    // CORRECT: @ManyToOne / @OneToOne default to EAGER — always make LAZY explicit.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private Author author;

    // For @OneToOne, @MapsId lets Hibernate resolve laziness via shared PK.
    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "id")
    private User user;

    public Long getId() { return id; }
    public Author getAuthor() { return author; }
    public User getUser() { return user; }
}
