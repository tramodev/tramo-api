// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.trail.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Entity
@Setter
@Getter
@NoArgsConstructor
@Table(indexes = {
        @Index(name = "idx_trail_item_trail", columnList = "trail_id"),
        @Index(name = "idx_trail_item_item", columnList = "item_id"),
})
public class TrailItem {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Trail trail;
    @ManyToOne(fetch = FetchType.LAZY)
    private Item item;

    int orderIndex;
}
