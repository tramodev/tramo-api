package com.tramo.backend.project.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ProjectVisibility {
    PRIVATE,
    UNLISTED,
    PUBLISHED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static ProjectVisibility fromJson(String value) {
        return ProjectVisibility.valueOf(value.toUpperCase());
    }
}
