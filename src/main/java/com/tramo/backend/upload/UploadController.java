package com.tramo.backend.upload;

import com.tramo.backend.upload.dto.UploadPresignRequestDTO;
import com.tramo.backend.upload.dto.UploadPresignResponseDTO;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {
    private static final Map<String, String> ALLOWED_CONTENT_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif"
    );

    private final R2Client r2Client;

    public UploadController(R2Client r2Client) {
        this.r2Client = r2Client;
    }

    @PostMapping("/presign")
    public ResponseEntity<UploadPresignResponseDTO> presign(@Valid @RequestBody UploadPresignRequestDTO request,
                                                              @AuthenticationPrincipal User user) {
        String extension = ALLOWED_CONTENT_TYPES.get(request.getContentType());
        if (extension == null) {
            throw new IllegalArgumentException("Unsupported content type: " + request.getContentType());
        }

        String key = "%s/%d/%s.%s".formatted(request.getKind(), user.getId(), request.getContentHash(), extension);
        String uploadUrl = r2Client.presignPut(key, request.getContentType());
        String publicUrl = r2Client.publicUrlFor(key);

        return ResponseEntity.ok(new UploadPresignResponseDTO(uploadUrl, publicUrl));
    }
}
