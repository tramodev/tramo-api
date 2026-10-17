package com.tramo.backend.project.dto;

import jakarta.validation.constraints.Size;

public record CreateProjectVersionRequest(@Size(max = 120) String name) {}
