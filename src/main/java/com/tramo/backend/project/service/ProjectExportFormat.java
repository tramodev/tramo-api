package com.tramo.backend.project.service;

import com.tramo.backend.exception.ProjectExportException;
import java.util.Locale;

public enum ProjectExportFormat {
    HTML("application/zip", ".zip"),
    MD("application/zip", "-md.zip"),
    PDF("application/pdf", ".pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx");

    private final String contentType;
    private final String suffix;
    ProjectExportFormat(String contentType, String suffix) { this.contentType = contentType; this.suffix = suffix; }
    public String contentType() { return contentType; }
    public String suffix() { return suffix; }

    public static ProjectExportFormat parse(String value) {
        for (ProjectExportFormat format : values()) if (format.name().toLowerCase(Locale.ROOT).equals(value)) return format;
        throw new ProjectExportException(400, "EXPORT_FORMAT_INVALID", "Unsupported export format.");
    }
}
