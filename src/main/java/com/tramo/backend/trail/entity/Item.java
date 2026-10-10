// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
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
@Table(indexes = {
        @Index(name = "idx_item_project", columnList = "project_id"),
})
public class Item {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private String title;
    private String type;
    private String titleAlign;
    private Date createdDate;
    private Date modifiedDate;

    
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    
    
    
    private Boolean unfiled = false;

    @OneToOne(cascade = CascadeType.ALL)
    private ItemContent content;
    @OneToMany(mappedBy = "item")
    List<TrailItem> trailItem;

    
    
}
