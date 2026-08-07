package com.tramo.backend.upload;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.security.ratelimit.RateLimiterService;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.upload.dto.UploadPresignRequestDTO;
import com.tramo.backend.upload.dto.UploadPresignResponseDTO;
import com.tramo.backend.upload.entity.UploadRecord;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.user.entity.User;
import io.github.bucket4j.Bucket;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
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
    private final ProjectRepository projectRepository;
    private final ProjectIdCodec projectIdCodec;
    private final RateLimiterService rateLimiterService;
    private final long maxUploadBytes;
    private final int maxUploadBytesPerHour;

    public UploadController(R2Client r2Client,
                            SubscriptionService subscriptionService,
                            UploadRecordRepository uploadRecordRepository,
                            ProjectRepository projectRepository,
                            ProjectIdCodec projectIdCodec,
                            RateLimiterService rateLimiterService,
                            @Value("${app.limits.max-upload-bytes}") long maxUploadBytes,
                            @Value("${app.limits.max-upload-bytes-per-hour}") int maxUploadBytesPerHour) {
        this.r2Client = r2Client;
        this.subscriptionService = subscriptionService;
        this.uploadRecordRepository = uploadRecordRepository;
        this.projectRepository = projectRepository;
        this.projectIdCodec = projectIdCodec;
        this.rateLimiterService = rateLimiterService;
        this.maxUploadBytes = maxUploadBytes;
        this.maxUploadBytesPerHour = maxUploadBytesPerHour;
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
                && !subscriptionService.isSupporter(user)) {
            throw new LimitExceededException("Animated GIF avatars are a supporter perk. Upgrade to use one.");
        }
        if ("banner".equals(request.getKind()) && "image/gif".equals(request.getContentType())) {
            throw new IllegalArgumentException("Animated GIF banners are not supported.");
        }
        if ("banner".equals(request.getKind()) && !subscriptionService.isSupporter(user)) {
            throw new LimitExceededException("Profile banners are a supporter perk. Upgrade to use one.");
        }
        subscriptionService.assertUploadAllowed(user, request.getContentBytes());

        Bucket byteBucket = rateLimiterService.resolveBucket(
                "upload-bytes:" + user.getId(), maxUploadBytesPerHour, maxUploadBytesPerHour, Duration.ofHours(1));
        if (!byteBucket.tryConsume(request.getContentBytes())) {
            throw new LimitExceededException("Upload throughput limit reached. Try again later.");
        }

        Long projectId = null;
        if (request.getProjectId() != null) {
            projectId = projectIdCodec.decode(request.getProjectId());
            if (!projectRepository.existsByIdAndOwnerId(projectId, user.getId())) {
                throw new AccessDeniedException("Not allowed to upload to this project");
            }
        }

        String key = "%s/%d/%s.%s".formatted(request.getKind(), user.getId(), request.getContentHash(), extension);
        recordUpload(user, projectId, key, request.getContentBytes());
        String uploadUrl = r2Client.presignPut(key, request.getContentType(), request.getContentBytes());
        String publicUrl = r2Client.publicUrlFor(key);

        return ResponseEntity.ok(new UploadPresignResponseDTO(uploadUrl, publicUrl));
    }

    
    private void recordUpload(User user, Long projectId, String key, long bytes) {
        if (uploadRecordRepository.updateByObjectKey(key, projectId, bytes) > 0) {
            return;
        }
        UploadRecord fresh = new UploadRecord();
        fresh.setUserId(user.getId());
        fresh.setObjectKey(key);
        fresh.setCreatedDate(new Date());
        fresh.setBytes(bytes);
        fresh.setProjectId(projectId);
        try {
            uploadRecordRepository.save(fresh);
        } catch (DataIntegrityViolationException concurrentInsert) {
            uploadRecordRepository.updateByObjectKey(key, projectId, bytes);
        }
    }
}
