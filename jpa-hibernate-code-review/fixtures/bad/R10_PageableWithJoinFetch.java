// FIXTURE: R10 — Pageable combined with JOIN FETCH on a *ToMany
// Expected severity: HIGH
// Expected finding: Pageable query combined with JOIN FETCH on a *ToMany triggers HHH000104 in-memory pagination

package com.example.fixtures.bad;

import jakarta.persistence.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PostRepository extends JpaRepository<Post, Long> {

    @Query("SELECT p FROM Post p LEFT JOIN FETCH p.comments WHERE p.title LIKE :q")
    Page<Post> searchWithComments(String q, Pageable pageable);
}
