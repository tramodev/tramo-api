package com.tramo.backend.project.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ProjectVisibilityConverter implements AttributeConverter<ProjectVisibility, String> {

    @Override
    public String convertToDatabaseColumn(ProjectVisibility visibility) {
        return visibility == null ? null : visibility.name().toLowerCase();
    }

    @Override
    public ProjectVisibility convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : ProjectVisibility.valueOf(dbValue.toUpperCase());
    }
}
