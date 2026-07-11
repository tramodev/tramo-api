package com.mypath.backend.path.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Idea {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private String title;
    private String type;
    private Date createdDate;
    private Date modifiedDate;
    @OneToOne(cascade = CascadeType.ALL)
    private IdeaContent content;
    @OneToMany(mappedBy = "idea")
    List<PathIdea> pathIdea;

    @OneToMany(mappedBy = "sourceIdea", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IdeaLink> outgoingLinks;

    @OneToMany(mappedBy = "targetIdea", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IdeaLink> incomingLinks;

}
