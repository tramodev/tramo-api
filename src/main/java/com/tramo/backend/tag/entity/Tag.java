// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.tag.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "tag", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class Tag {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean official;

    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long usageCount;

    private Long createdBy;

    public Tag(String name, boolean official, Long createdBy) {
        this.name = name;
        this.official = official;
        this.createdBy = createdBy;
        this.usageCount = 0;
    }
}
