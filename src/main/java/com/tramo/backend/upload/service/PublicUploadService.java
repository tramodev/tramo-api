package com.tramo.backend.upload.service;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.common.SafeLog;
import com.tramo.backend.exception.*;
import com.tramo.backend.project.service.AccessGuard;
import com.tramo.backend.security.ratelimit.RateLimiterService;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.upload.PrivateImageStorage;
import com.tramo.backend.upload.R2Client;
import com.tramo.backend.upload.dto.*;
import com.tramo.backend.upload.entity.EditorImage;
import com.tramo.backend.upload.entity.PublicUpload;
import com.tramo.backend.upload.entity.UploadRecord;
import com.tramo.backend.upload.repository.EditorImageRepository;
import com.tramo.backend.upload.repository.PublicUploadRepository;
import com.tramo.backend.upload.repository.UploadRecordRepository;
import com.tramo.backend.user.entity.User;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class PublicUploadService {
    private static final Map<String, String> EXTENSIONS = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp", "image/gif", "gif");
    private final PublicUploadRepository uploads;
    private final UploadRecordRepository records;
    private final PrivateImageStorage privateStorage;
    private final R2Client publicStorage;
    private final EditorImageRepository operations;
    private final SubscriptionService subscriptions;
    private final AccessGuard access;
    private final ProjectIdCodec codec;
    private final RateLimiterService limiter;
    private final TransactionTemplate transactions;
    private final long maxBytes;
    private final int bytesPerHour;

    public PublicUploadService(PublicUploadRepository uploads, UploadRecordRepository records, PrivateImageStorage privateStorage,
            R2Client publicStorage, EditorImageRepository operations, SubscriptionService subscriptions, AccessGuard access,
            ProjectIdCodec codec, RateLimiterService limiter, PlatformTransactionManager transactionManager,
            @Value("${app.limits.max-upload-bytes}") long maxBytes, @Value("${app.limits.max-upload-bytes-per-hour}") int bytesPerHour) {
        this.uploads = uploads; this.records = records; this.privateStorage = privateStorage; this.publicStorage = publicStorage;
        this.operations = operations; this.subscriptions = subscriptions; this.access = access; this.codec = codec; this.limiter = limiter;
        this.transactions = new TransactionTemplate(transactionManager); this.maxBytes = maxBytes; this.bytesPerHour = bytesPerHour;
    }

    public UploadPresignResponseDTO presign(UploadPresignRequestDTO request, User user) {
        String extension = EXTENSIONS.get(request.getContentType());
        if (extension == null) throw new RequestValidationException(RequestErrorCode.CONTENT_TYPE_UNSUPPORTED);
        if (request.getContentBytes() > maxBytes) throw new RequestValidationException(RequestErrorCode.UPLOAD_SIZE_LIMIT_EXCEEDED);
        boolean supporter = checkPerks(request.getKind(), request.getContentType(), user);
        Long projectId = request.getProjectId() == null ? null : access.getOwnedProject(codec.decode(request.getProjectId()), user).getId();
        if (!limiter.resolveBucket("upload-bytes:" + user.getId(), bytesPerHour, bytesPerHour, Duration.ofHours(1))
                .tryConsume(request.getContentBytes())) throw new LimitExceededException("Upload throughput limit reached. Try again later.");
        PublicUpload upload = transactions.execute(status -> {
            subscriptions.lockStorage(user);
            PublicUpload existing = uploads.findByOwnerIdAndKindAndContentHash(user.getId(), request.getKind(), request.getContentHash()).orElse(null);
            if (existing != null) {
                if ("DELETING".equals(existing.getState())) throw new RequestValidationException(RequestErrorCode.UPLOAD_IN_PROGRESS);
                if (!existing.getContentType().equals(request.getContentType()) || record(existing).getBytes() != request.getContentBytes())
                    throw new RequestValidationException(RequestErrorCode.IMAGE_UPLOAD_METADATA_MISMATCH);
                if ("PENDING".equals(existing.getState())) existing.setRequestedAt(new Date());
                return existing;
            }
            subscriptions.assertUploadAllowed(user, request.getContentBytes(), supporter);
            UUID id = UUID.randomUUID();
            UploadRecord record = new UploadRecord();
            record.setUserId(user.getId()); record.setProjectId(projectId); record.setBytes(request.getContentBytes()); record.setCreatedDate(new Date());
            record.setObjectKey("%s/%d/%s-%s.%s".formatted(request.getKind(), user.getId(), request.getContentHash(), id, extension));
            records.saveAndFlush(record);
            PublicUpload fresh = new PublicUpload();
            fresh.setId(id); fresh.setOwnerId(user.getId()); fresh.setUploadRecordId(record.getId()); fresh.setKind(request.getKind());
            fresh.setContentType(request.getContentType()); fresh.setContentHash(request.getContentHash()); fresh.setState("PENDING"); fresh.setRequestedAt(new Date());
            return uploads.save(fresh);
        });
        String url = "READY".equals(upload.getState()) ? null : privateStorage.presignUpload(image(upload, record(upload)));
        return new UploadPresignResponseDTO(upload.getId(), url);
    }

    public UploadCompleteResponseDTO complete(UUID id, User user) {
        owned(id, user);
        return operations.withObjectLock(id, () -> {
            PublicUpload upload = owned(id, user);
            UploadRecord record = record(upload);
            if ("READY".equals(upload.getState())) return new UploadCompleteResponseDTO(publicStorage.publicUrlFor(record.getObjectKey()));
            if (!"PENDING".equals(upload.getState())) throw new RequestValidationException(RequestErrorCode.UPLOAD_ATTEMPT_EXPIRED);
            checkPerks(upload.getKind(), upload.getContentType(), user);
            if (record.getProjectId() != null) access.getOwnedProject(record.getProjectId(), user);
            try {
                var validated = privateStorage.validateTemporary(id, upload.getContentType(), record.getBytes(), upload.getContentHash());
                publicStorage.publishValidated(record.getObjectKey(), upload.getContentType(), validated);
            } catch (S3Exception failure) {
                if (failure.statusCode() == 404) throw new ResourceNotFoundException("Uploaded file not found");
                if (failure.statusCode() == 412) throw new RequestValidationException(RequestErrorCode.IMAGE_UPLOAD_METADATA_MISMATCH);
                throw failure;
            }
            transactions.executeWithoutResult(status -> {
                PublicUpload current = uploads.findForUpdate(id).orElseThrow(this::missing);
                current.setState("READY");
            });
            return new UploadCompleteResponseDTO(publicStorage.publicUrlFor(record.getObjectKey()));
        });
    }

    private boolean checkPerks(String kind, String type, User user) {
        boolean supporter = subscriptions.isSupporter(user);
        if ("banner".equals(kind) && "image/gif".equals(type)) throw new RequestValidationException(RequestErrorCode.ANIMATED_BANNER_UNSUPPORTED);
        if ("banner".equals(kind) && !supporter) throw new LimitExceededException("Profile banners are a supporter perk. Upgrade to use one.");
        if ("avatar".equals(kind) && "image/gif".equals(type) && !supporter)
            throw new LimitExceededException("Animated GIF avatars are a supporter perk. Upgrade to use one.");
        return supporter;
    }

    private PublicUpload owned(UUID id, User user) {
        PublicUpload upload = uploads.findById(id).orElseThrow(this::missing);
        if (!upload.getOwnerId().equals(user.getId())) throw new AccessDeniedException("Not allowed to confirm this upload");
        return upload;
    }

    private UploadRecord record(PublicUpload upload) { return records.findById(upload.getUploadRecordId()).orElseThrow(this::missing); }
    private ResourceNotFoundException missing() { return new ResourceNotFoundException("Upload not found"); }
    private EditorImage image(PublicUpload upload, UploadRecord record) {
        return new EditorImage(upload.getId(), upload.getId(), record.getProjectId(), upload.getContentType(), record.getBytes(), upload.getState(), upload.getContentHash());
    }

    @Scheduled(fixedDelay = 3600000)
    public void purge() {
        Date cutoff = new Date(System.currentTimeMillis() - Duration.ofDays(1).toMillis());
        for (UUID id : uploads.findCleanupCandidates(cutoff, PageRequest.of(0, 500))) {
            try {
                operations.withObjectLock(id, () -> {
                    PublicUpload upload = transactions.execute(status -> {
                        PublicUpload current = uploads.findForUpdate(id).orElse(null);
                        if (current == null || current.getRequestedAt().after(cutoff)) return null;
                        if ("PENDING".equals(current.getState())) current.setState("DELETING");
                        return current;
                    });
                    if (upload == null) return null;
                    if ("READY".equals(upload.getState())) {
                        privateStorage.deleteTemporary(id);
                        transactions.executeWithoutResult(status -> uploads.findForUpdate(id).ifPresent(current -> current.setTemporaryCleaned(true)));
                    } else {
                        UploadRecord record = record(upload);
                        publicStorage.deleteByPublicUrl(publicStorage.publicUrlFor(record.getObjectKey()), upload.getOwnerId());
                    }
                    return null;
                });
            } catch (RuntimeException failure) {
                SafeLog.failure(LoggerFactory.getLogger(PublicUploadService.class), "public_upload_purge_failed", "STORAGE_DELETE_FAILED", failure);
            }
        }
    }
}
