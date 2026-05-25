// FIXTURE: R08 — @LazyCollection(LazyCollectionOption.EXTRA)
// Expected severity: HIGH
// Expected finding: @LazyCollection(EXTRA) issues a fresh SQL query for every size()/contains() access

package com.example.fixtures.bad;

import jakarta.persistence.*;
import org.hibernate.annotations.LazyCollection;
import org.hibernate.annotations.LazyCollectionOption;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    private String title;

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    @LazyCollection(LazyCollectionOption.EXTRA)
    private List<Comment> comments = new ArrayList<>();

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public List<Comment> getComments() { return comments; }
}
