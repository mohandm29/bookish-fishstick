// FIXTURE: R14 — clean implementation
// This file demonstrates the CORRECT pattern for rule R14.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R14.
package com.example.fixtures.good;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    // CORRECT: a single DML statement. No load-into-memory-then-delete-one-by-one
    // (which would issue N+1 selects plus N deletes).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Comment c where c.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
