package com.tramo.backend.project.controller;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.project.service.ProjectExportFormat;
import com.tramo.backend.project.service.ProjectExportService;
import com.tramo.backend.user.entity.User;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.file.Files;

@RestController
@RequestMapping("/api/project")
public class ProjectExportController {
    private final ProjectExportService exports;
    private final ProjectIdCodec codec;
    public ProjectExportController(ProjectExportService exports, ProjectIdCodec codec) { this.exports = exports; this.codec = codec; }

    @GetMapping("/{id}/export")
    public void export(@PathVariable String id, @RequestParam(defaultValue = "html") String format, @AuthenticationPrincipal User user, HttpServletResponse response) throws IOException {
        var type = ProjectExportFormat.parse(format);
        var archive = exports.prepare(codec.decode(id), user, type);
        try {
            response.setContentType(type.contentType());
            response.setHeader("Content-Disposition", "attachment; filename=\"tramo-project-" + codec.encode(codec.decode(id)) + type.suffix() + "\"");
            response.setHeader("Cache-Control", "private, no-store");
            response.setContentLengthLong(Files.size(archive));
            Files.copy(archive, response.getOutputStream());
        } finally { exports.discard(archive); }
    }
}
