// FIXTURE: R02 — Unidirectional @OneToMany(cascade = CascadeType.ALL) without mappedBy
// Expected severity: HIGH
// Expected finding: Unidirectional @OneToMany without mappedBy generates an extra join table and pathological SQL

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

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Comment> comments = new ArrayList<>();

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public List<Comment> getComments() { return comments; }
}
