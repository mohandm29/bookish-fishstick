// FIXTURE: R09 — JPQL JOIN FETCH of two unrelated *ToMany collections
// Expected severity: CRITICAL
// Expected finding: Query JOIN FETCHes two *ToMany collections producing a Cartesian product / MultipleBagFetchException

package com.example.fixtures.bad;

import jakarta.persistence.*;
import java.util.List;

public class PostRepository {

    @PersistenceContext
    private EntityManager em;

    public Post findWithCommentsAndTags(Long id) {
        return em.createQuery(
                "SELECT p FROM Post p " +
                "LEFT JOIN FETCH p.comments c " +
                "LEFT JOIN FETCH p.tags t " +
                "WHERE p.id = :id", Post.class)
            .setParameter("id", id)
            .getSingleResult();
    }

    public List<Post> findAllWithCommentsAndTags() {
        return em.createQuery(
                "SELECT DISTINCT p FROM Post p " +
                "LEFT JOIN FETCH p.comments " +
                "LEFT JOIN FETCH p.tags", Post.class)
            .getResultList();
    }
}
