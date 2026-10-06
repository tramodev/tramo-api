// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.dto;

import com.tramo.backend.trail.dto.AssociationDTO;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
public class PublicItemDTO {
    private Long id;
    private String title;
    private String type;
    private String content;
    private String titleAlign;
    
    private String annotation;
    private String associationId;
    
    
    private List<AssociationDTO> associations;
}
