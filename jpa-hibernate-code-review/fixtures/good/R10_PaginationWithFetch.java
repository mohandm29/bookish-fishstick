// FIXTURE: R10 — clean implementation
// This file demonstrates the CORRECT pattern for rule R10.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R10.
package com.example.fixtures.good;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface PostRepository extends JpaRepository<Post, Long> {

    // CORRECT step 1: page IDs only — no collection fetch, so the DB does pagination.
    @Query("select p.id from Post p")
    Page<Long> findPostIds(Pageable pageable);

    // CORRECT step 2: fetch the page's entities with their collections. `distinct`
    // collapses the Cartesian rows; `in :ids` keeps the result set bounded.
    @Query("select distinct p from Post p left join fetch p.comments where p.id in :ids")
    List<Post> findPostsWithComments(@Param("ids") List<Long> ids);
}

// Service:
//
// Page<Long> idPage = repo.findPostIds(pageable);
// List<Post> posts  = repo.findPostsWithComments(idPage.getContent());
// return new PageImpl<>(posts, pageable, idPage.getTotalElements());
//
// No HHH000104 warning — Hibernate is no longer paginating a fetched collection in memory.
