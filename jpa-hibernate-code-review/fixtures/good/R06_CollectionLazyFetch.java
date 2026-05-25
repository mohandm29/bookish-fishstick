// FIXTURE: R06 — clean implementation
// This file demonstrates the CORRECT pattern for rule R06.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R06.
package com.example.fixtures.good;

import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;

@Entity
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post_seq")
    @SequenceGenerator(name = "post_seq", sequenceName = "post_seq", allocationSize = 50)
    private Long id;

    // CORRECT: collections default to LAZY and stay LAZY. Callers that need the
    // children use JOIN FETCH or an @EntityGraph at query time.
    @OneToMany(mappedBy = "post", fetch = FetchType.LAZY)
    private Set<Comment> comments = new HashSet<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "post_tag",
        joinColumns = @JoinColumn(name = "post_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new HashSet<>();

    public Long getId() { return id; }
    public Set<Comment> getComments() { return comments; }
    public Set<Tag> getTags() { return tags; }
}
