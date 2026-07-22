package com.tramo.backend.trail.entity;

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
public class Item {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private String title;
    private String type;
    private String titleAlign;
    private Date createdDate;
    private Date modifiedDate;
    @OneToOne(cascade = CascadeType.ALL)
    private ItemContent content;
    @OneToMany(mappedBy = "item")
    List<TrailItem> trailItem;

    @OneToMany(mappedBy = "sourceItem", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Association> outgoingLinks;

    @OneToMany(mappedBy = "targetItem", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Association> incomingLinks;

}
