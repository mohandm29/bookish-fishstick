// FIXTURE: R06 — @OneToMany or @ManyToMany with fetch = FetchType.EAGER
// Expected severity: HIGH
// Expected finding: @OneToMany declared with FetchType.EAGER loads the full collection on every parent read

package com.example.fixtures.bad;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Author {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    private String name;

    @OneToMany(mappedBy = "author", fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    private List<Post> posts = new ArrayList<>();

    @ManyToMany(fetch = FetchType.EAGER)
    private List<Tag> favoriteTags = new ArrayList<>();

    public Long getId() { return id; }
    public String getName() { return name; }
    public List<Post> getPosts() { return posts; }
    public List<Tag> getFavoriteTags() { return favoriteTags; }
}
