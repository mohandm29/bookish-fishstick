// FIXTURE: R01 — @ManyToMany with CascadeType.ALL or CascadeType.REMOVE
// Expected severity: CRITICAL
// Expected finding: @ManyToMany uses CascadeType.ALL which cascades REMOVE to shared associated entities

package com.example.fixtures.bad;

import jakarta.persistence.*;
import java.util.HashSet;
import java.util.Set;

@Entity
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    private String title;

    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(
        name = "post_tag",
        joinColumns = @JoinColumn(name = "post_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private Set<Tag> tags = new HashSet<>();

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Set<Tag> getTags() { return tags; }
}
