// FIXTURE: R17 — clean implementation
// This file demonstrates the CORRECT pattern for rule R17.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R17.
package com.example.fixtures.good;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class ProductService {

    private final ProductRepository repo;

    public ProductService(ProductRepository repo) { this.repo = repo; }

    // CORRECT: read paths declare readOnly=true. Hibernate skips dirty checking
    // and flushes, and the driver may route to a read replica.
    @Transactional(readOnly = true)
    public List<Product> search(String term) {
        return repo.findByNameContaining(term);
    }

    // Write paths use the default (readOnly=false).
    @Transactional
    public Product create(String sku, String name) {
        return repo.save(new Product(sku, name));
    }
}
