// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.collab.repository;

import com.tramo.backend.collab.entity.ItemYdoc;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemYdocRepository extends JpaRepository<ItemYdoc, Long> {
}
