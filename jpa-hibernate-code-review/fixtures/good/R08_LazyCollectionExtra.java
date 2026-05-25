// FIXTURE: R08 — clean implementation
// This file demonstrates the CORRECT pattern for rule R08.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R08.
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

    // CORRECT: plain LAZY collection. No @LazyCollection(EXTRA) — deprecated in
    // Hibernate 6 and silently fired one query per .size()/.contains() call.
    @OneToMany(mappedBy = "post", fetch = FetchType.LAZY)
    private Set<Comment> comments = new HashSet<>();

    public Long getId() { return id; }
    public Set<Comment> getComments() { return comments; }
}

// In the service layer, derive size from a count query instead of collection.size():
//
// public long countCommentsForPost(Long postId) {
//     return em.createQuery(
//         "select count(c) from Comment c where c.post.id = :id", Long.class)
//         .setParameter("id", postId)
//         .getSingleResult();
// }
