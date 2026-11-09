package com.tramo.backend.project.dto;

import java.util.List;

public record ProjectStructureDTO(long version, long restoreEpoch, List<EditorBootstrapDTO.EditorTrailDTO> trails,
        List<EditorBootstrapDTO.EditorItemDTO> items) {}
