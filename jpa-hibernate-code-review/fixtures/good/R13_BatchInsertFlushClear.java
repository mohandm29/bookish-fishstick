// FIXTURE: R13 — clean implementation
// This file demonstrates the CORRECT pattern for rule R13.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R13.
package com.example.fixtures.good;

import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class OrderImportService {

    @PersistenceContext
    private EntityManager em;

    // CORRECT: flush + clear at a fixed batch size keeps the persistence context
    // bounded; combined with hibernate.jdbc.batch_size this produces real JDBC batches.
    @Transactional
    public void saveAll(List<Order> orders) {
        final int batchSize = 50;
        for (int i = 0; i < orders.size(); i++) {
            em.persist(orders.get(i));
            if (i > 0 && i % batchSize == 0) {
                em.flush();
                em.clear();
            }
        }
        em.flush();
        em.clear();
    }
}
