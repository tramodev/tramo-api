package com.tramo.backend.exception;

public class ProjectExportException extends RuntimeException {
    private final int status;
    private final String code;
    public ProjectExportException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static ProjectExportException tooLarge() {
        return new ProjectExportException(413, "EXPORT_TOO_LARGE", "Project export exceeds the supported size or resource limits.");
    }
    public static ProjectExportException resource(String reference) {
        return new ProjectExportException(409, "EXPORT_RESOURCE_UNAVAILABLE", "Cannot export required resource: " + reference + ". Please restore it and try again.");
    }
}
