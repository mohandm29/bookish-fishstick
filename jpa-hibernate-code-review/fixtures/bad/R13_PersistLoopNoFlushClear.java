// FIXTURE: R13 — Loop with persist() / save() and no flush()/clear() cadence
// Expected severity: HIGH
// Expected finding: Loop persists many entities with no periodic flush()/clear(), causing O(N^2) dirty-check and OOM

package com.example.fixtures.bad;

import jakarta.persistence.*;
import java.util.List;

public class ProductImportService {

    @PersistenceContext
    private EntityManager em;

    public void importProducts(List<String> skus) {
        for (int i = 0; i < skus.size(); i++) {
            Product p = new Product();
            p.setSku(skus.get(i));
            p.setName("Product-" + i);
            em.persist(p);
        }
    }

    public void importMore(List<Product> products) {
        for (Product p : products) {
            em.persist(p);
        }
    }
}
