// FIXTURE: R17 — @Transactional on a query-only method without readOnly = true
// Expected severity: LOW
// Expected finding: Query-only method annotated @Transactional without readOnly=true misses snapshot/replica optimizations

package com.example.fixtures.bad;

import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class PostQueryService {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public List<Post> findRecent(int limit) {
        return em.createQuery("SELECT p FROM Post p ORDER BY p.id DESC", Post.class)
            .setMaxResults(limit)
            .getResultList();
    }

    @Transactional
    public Post findById(Long id) {
        return em.find(Post.class, id);
    }
}
