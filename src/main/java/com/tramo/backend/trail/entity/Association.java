// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(
        uniqueConstraints = @UniqueConstraint(columnNames = {"source_item_id", "target_type", "target_id"}),
        indexes = @Index(name = "idx_association_target", columnList = "target_type, target_id")
)
public class Association {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_item_id")
    private Item sourceItem;

    @Enumerated(EnumType.STRING)
    private AssociationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type")
    private AssociationTargetType targetType;

    @Column(name = "target_id")
    private Long targetId;

    private Date createdDate;
}
