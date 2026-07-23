package com.tramo.backend.trail.entity;

import com.tramo.backend.project.entity.Project;
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

    // The project this item belongs to. Lets an item exist without any trail
    // ("loose"). Null on legacy items, which resolve ownership via TrailItem.
    @ManyToOne
    @JoinColumn(name = "project_id")
    private Project project;

    // Sticky "Unfiled" membership: set when created loose or when detached from
    // its last trail; NOT cleared by attaching to a trail (an item can be in
    // Unfiled and in trails at once). Boolean wrapper: null (legacy rows) = false.
    private Boolean unfiled = false;

    @OneToOne(cascade = CascadeType.ALL)
    private ItemContent content;
    @OneToMany(mappedBy = "item")
    List<TrailItem> trailItem;

    // Outgoing associations (this item as source). Incoming ones are polymorphic
    // (target_type/target_id), so they are cleaned explicitly in the service layer.
    @OneToMany(mappedBy = "sourceItem", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Association> outgoingLinks;

}
