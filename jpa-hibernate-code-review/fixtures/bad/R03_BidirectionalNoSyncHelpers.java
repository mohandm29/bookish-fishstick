// FIXTURE: R03 — Bidirectional association without addX / removeX sync helpers
// Expected severity: HIGH
// Expected finding: Bidirectional @OneToMany lacks addComment/removeComment helpers to keep both sides in sync

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

    @OneToMany(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Comment> comments = new ArrayList<>();

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public List<Comment> getComments() { return comments; }
    public void setComments(List<Comment> comments) { this.comments = comments; }
}
