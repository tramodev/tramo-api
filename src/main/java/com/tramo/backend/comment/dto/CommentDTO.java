// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.comment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@AllArgsConstructor
public class CommentDTO {
    private Long id;
    private String content;
    private boolean deleted;
    private String authorUsername;
    private String authorAvatar;
    private String authorBadge;
    private Long parentId;
    private Date createdDate;
    private boolean canDelete;
}
