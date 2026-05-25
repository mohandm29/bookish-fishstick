// FIXTURE: R01 — clean implementation
// This file demonstrates the CORRECT pattern for rule R01.
// A code review against this file should produce 0 CRITICAL/HIGH findings for R01.
package com.example.fixtures.good;

import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;

@Entity
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "post_seq")
    @SequenceGenerator(name = "post_seq", sequenceName = "post_seq", allocationSize = 50)
    private Long id;

    private String title;

    // CORRECT: only PERSIST + MERGE on @ManyToMany. REMOVE/ALL would delete shared Tags.
    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    @JoinTable(
        name = "post_tag",
        joinColumns = @JoinColumn(name = "post_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new HashSet<>();

    public Long getId() { return id; }
    public Set<Tag> getTags() { return tags; }
}
