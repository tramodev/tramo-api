package com.tramo.backend.trail.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Entity
@Setter
@Getter
@NoArgsConstructor
public class TrailItem {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne
    private Trail trail;
    @ManyToOne
    private Item item;

    int orderIndex;

    // Human text linking this step to the next — what makes a trail studyable on its own.
    @Column(columnDefinition = "TEXT")
    private String annotation;

    // Which graph association was used to jump here from the previous step; null = deliberate jump.
    @ManyToOne
    private Association association;
}
