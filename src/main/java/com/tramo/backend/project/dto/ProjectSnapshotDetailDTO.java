// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.dto;

import com.tramo.backend.project.snapshot.ProjectSnapshotData;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class ProjectSnapshotDetailDTO {
    private Long id;
    private int version;
    private Date createdDate;
    private ProjectSnapshotData content;
}
