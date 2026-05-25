// FIXTURE: R02 — clean implementation
// This file demonstrates the CORRECT pattern for rule R02.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R02.
package com.example.fixtures.good;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "posts")
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post_seq")
    @SequenceGenerator(name = "post_seq", sequenceName = "post_seq", allocationSize = 50)
    private Long id;

    private String title;

    // CORRECT: bidirectional, child owns the FK via mappedBy. No extra join table,
    // delete semantics are clean, orphanRemoval handles child cleanup.
    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Comment> comments = new ArrayList<>();

    public Long getId() { return id; }
    public List<Comment> getComments() { return comments; }
}
