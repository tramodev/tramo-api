package com.tramo.backend.project.service;

import com.tramo.backend.exception.ProjectExportException;
import java.util.Locale;

public enum ProjectExportFormat {
    HTML("application/zip", " (HTML)", ".zip"),
    MD("application/zip", " (Markdown)", ".zip"),
    PDF("application/pdf", "", ".pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "", ".docx");

    private final String contentType;
    private final String label;
    private final String extension;
    ProjectExportFormat(String contentType, String label, String extension) { this.contentType = contentType; this.label = label; this.extension = extension; }
    public String contentType() { return contentType; }
    public String extension() { return extension; }

    public String filename(String title) {
        String name = (title == null ? "" : title).replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", " ").replaceAll("\\s+", " ").strip();
        if (name.codePointCount(0, name.length()) > 100) name = name.substring(0, name.offsetByCodePoints(0, 100));
        name = name.replaceAll("^[. ]+|[. ]+$", "");
        if (name.isEmpty() || name.matches("(?i)(con|prn|aux|nul|com\\d|lpt\\d)(\\..*)?")) name = "Untitled project";
        return name + label + extension;
    }

    public static ProjectExportFormat parse(String value) {
        for (ProjectExportFormat format : values()) if (format.name().toLowerCase(Locale.ROOT).equals(value)) return format;
        throw new ProjectExportException(400, "EXPORT_FORMAT_INVALID", "Unsupported export format.");
    }
}
