// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class ItemYdoc {
    @Id
    private Long itemId;

    @Column(nullable = false)
    private byte[] state;

    @Column(nullable = false)
    private int extractionEpoch;

    @Column(nullable = false)
    private Date updatedAt;

    public ItemYdoc(Long itemId) {
        this.itemId = itemId;
    }
}
