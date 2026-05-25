// FIXTURE: R04 — List used for a @ManyToMany association
// Expected severity: MEDIUM
// Expected finding: @ManyToMany declared as List<Tag> causes bag delete-all-then-reinsert behavior on update

package com.example.fixtures.bad;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    private String title;

    @ManyToMany(cascade = { CascadeType.PERSIST, CascadeType.MERGE })
    @JoinTable(
        name = "post_tag",
        joinColumns = @JoinColumn(name = "post_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private List<Tag> tags = new ArrayList<>();

    public Long getId() { return id; }
    public List<Tag> getTags() { return tags; }
}
