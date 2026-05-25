// FIXTURE: R07 — @ManyToOne / @OneToOne without explicit fetch = FetchType.LAZY
// Expected severity: MEDIUM
// Expected finding: @ManyToOne and @OneToOne without explicit FetchType.LAZY default to EAGER

package com.example.fixtures.bad;

import jakarta.persistence.*;

@Entity
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    private String body;

    @ManyToOne
    @JoinColumn(name = "post_id")
    private Post post;

    @OneToOne
    @JoinColumn(name = "author_id")
    private Author author;

    public Long getId() { return id; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Post getPost() { return post; }
    public void setPost(Post post) { this.post = post; }
    public Author getAuthor() { return author; }
    public void setAuthor(Author author) { this.author = author; }
}
