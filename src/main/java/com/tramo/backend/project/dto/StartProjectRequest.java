package com.tramo.backend.project.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record StartProjectRequest(@NotNull UUID requestId, boolean example) {}
