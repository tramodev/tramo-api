package com.tramo.backend.trail.dto;

import jakarta.validation.constraints.Size;

public record AssociationTextDTO(@Size(max = 2000) String text) {}
