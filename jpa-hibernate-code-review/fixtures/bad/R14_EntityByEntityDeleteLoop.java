// FIXTURE: R14 — Entity-by-entity delete loop instead of bulk @Modifying @Query
// Expected severity: MEDIUM
// Expected finding: Per-entity delete loop where a single bulk JPQL DELETE would suffice

package com.example.fixtures.bad;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.List;

public class CommentCleanupService {

    @PersistenceContext
    private EntityManager em;

    public void deleteOldComments(LocalDate cutoff) {
        List<Comment> old = em.createQuery(
                "SELECT c FROM Comment c WHERE c.createdAt < :cutoff", Comment.class)
            .setParameter("cutoff", cutoff)
            .getResultList();

        for (Comment c : old) {
            em.remove(c);
        }
    }
}
