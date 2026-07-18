package com.tramo.backend.path.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"source_idea_id", "target_idea_id"}))
public class IdeaLink {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "source_idea_id")
    private Idea sourceIdea;

    @ManyToOne
    @JoinColumn(name = "target_idea_id")
    private Idea targetIdea;

    private Date createdDate;
}
