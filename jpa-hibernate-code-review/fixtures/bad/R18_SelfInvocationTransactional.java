// FIXTURE: R18 — Self-invocation of @Transactional method (proxy bypass)
// Expected severity: HIGH
// Expected finding: Self-invocation of @Transactional method bypasses Spring proxy; nested @Transactional has no effect

package com.example.fixtures.bad;

import jakarta.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class OrderService {

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public void processBatch(List<Order> orders) {
        for (Order o : orders) {
            this.processOne(o);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processOne(Order o) {
        o.setStatus("PROCESSED");
        em.merge(o);
    }
}
