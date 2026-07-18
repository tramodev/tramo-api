package com.tramo.backend.project.dto;

import java.util.List;

public record PageResponseDTO<T>(List<T> content, boolean hasMore) {
}
