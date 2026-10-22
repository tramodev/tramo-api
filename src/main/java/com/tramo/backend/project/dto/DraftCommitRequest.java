package com.tramo.backend.project.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record DraftCommitRequest(@NotNull UUID clientRequestId,
        @NotNull @Valid NamedEntity project, @NotNull @Valid NamedEntity trail,
        @NotNull @Valid DraftItem item) {
    public record NamedEntity(@NotNull @Size(max = 255) String title) {}
    public record DraftItem(@NotNull @Size(max = 255) String title,
            @NotNull @Pattern(regexp = "left|center|right") String titleAlign,
            @NotNull String content) {}
}
