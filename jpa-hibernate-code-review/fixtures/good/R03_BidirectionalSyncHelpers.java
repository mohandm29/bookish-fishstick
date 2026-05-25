// FIXTURE: R03 — clean implementation
// This file demonstrates the CORRECT pattern for rule R03.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R03.
package com.example.fixtures.good;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post_seq")
    @SequenceGenerator(name = "post_seq", sequenceName = "post_seq", allocationSize = 50)
    private Long id;

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Comment> comments = new ArrayList<>();

    // CORRECT: sync helpers keep both sides of the association consistent in memory.
    public void addComment(Comment c) {
        comments.add(c);
        c.setPost(this);
    }

    public void removeComment(Comment c) {
        comments.remove(c);
        c.setPost(null);
    }

    public Long getId() { return id; }
    public List<Comment> getComments() { return comments; }
}
