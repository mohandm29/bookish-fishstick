// FIXTURE: R09 — clean implementation
// This file demonstrates the CORRECT pattern for rule R09.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R09.
package com.example.fixtures.good;

import jakarta.persistence.*;

public class PostRepository {

    @PersistenceContext
    private EntityManager em;

    // CORRECT: avoid MultipleBagFetchException and Cartesian explosion by issuing
    // two separate JOIN FETCH queries. Hibernate populates both collections on the
    // same managed Post instance from the persistence context.
    public Post loadWithCommentsAndTags(Long id) {
        Post post = em.createQuery(
                "select p from Post p join fetch p.comments where p.id = :id", Post.class)
            .setParameter("id", id)
            .getSingleResult();

        em.createQuery(
                "select p from Post p join fetch p.tags where p.id = :id", Post.class)
            .setParameter("id", id)
            .getSingleResult();

        return post;
    }
}
