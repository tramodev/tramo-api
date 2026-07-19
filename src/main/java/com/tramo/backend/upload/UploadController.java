package com.tramo.backend.upload;

import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.upload.dto.UploadPresignRequestDTO;
import com.tramo.backend.upload.dto.UploadPresignResponseDTO;
import com.tramo.backend.upload.entity.UploadRecord;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.user.entity.User;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
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
    private final SubscriptionService subscriptionService;
    private final UploadRecordRepository uploadRecordRepository;
    private final long maxUploadBytes;

    public UploadController(R2Client r2Client,
                            SubscriptionService subscriptionService,
                            UploadRecordRepository uploadRecordRepository,
                            @Value("${app.limits.max-upload-bytes}") long maxUploadBytes) {
        this.r2Client = r2Client;
        this.subscriptionService = subscriptionService;
        this.uploadRecordRepository = uploadRecordRepository;
        this.maxUploadBytes = maxUploadBytes;
    }

    @PostMapping("/presign")
    public ResponseEntity<UploadPresignResponseDTO> presign(@Valid @RequestBody UploadPresignRequestDTO request,
                                                              @AuthenticationPrincipal User user) {
        String extension = ALLOWED_CONTENT_TYPES.get(request.getContentType());
        if (extension == null) {
            throw new IllegalArgumentException("Unsupported content type: " + request.getContentType());
        }
        if (request.getContentBytes() > maxUploadBytes) {
            throw new IllegalArgumentException(
                    "File too large (max %dMB per upload)".formatted(maxUploadBytes / (1024 * 1024)));
        }
        if ("avatar".equals(request.getKind()) && "image/gif".equals(request.getContentType())
                && !subscriptionService.isPremium(user)) {
            throw new LimitExceededException("Animated GIF avatars are a premium perk. Upgrade to use one.");
        }
        subscriptionService.assertUploadAllowed(user, request.getContentBytes());

        String key = "%s/%d/%s.%s".formatted(request.getKind(), user.getId(), request.getContentHash(), extension);
        recordUpload(user, key, request.getContentBytes());
        String uploadUrl = r2Client.presignPut(key, request.getContentType());
        String publicUrl = r2Client.publicUrlFor(key);

        return ResponseEntity.ok(new UploadPresignResponseDTO(uploadUrl, publicUrl));
    }

    // Content-addressed keys mean re-uploading identical bytes hits the same key — upsert, never double-count.
    private void recordUpload(User user, String key, long bytes) {
        UploadRecord record = uploadRecordRepository.findByObjectKey(key).orElseGet(() -> {
            UploadRecord fresh = new UploadRecord();
            fresh.setUserId(user.getId());
            fresh.setObjectKey(key);
            fresh.setCreatedDate(new Date());
            return fresh;
        });
        record.setBytes(bytes);
        uploadRecordRepository.save(record);
    }
}
