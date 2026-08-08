package com.tramo.backend.upload;

import com.tramo.backend.trail.repository.ItemImageReferenceRepository;
import com.tramo.backend.upload.entity.PendingImageDeletion;
import com.tramo.backend.upload.repository.PendingImageDeletionRepository;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class ImageDeletionQueue {
    private final PendingImageDeletionRepository pendingImageDeletionRepository;
    private final ItemImageReferenceRepository itemImageReferenceRepository;

    public ImageDeletionQueue(PendingImageDeletionRepository pendingImageDeletionRepository,
                              ItemImageReferenceRepository itemImageReferenceRepository) {
        this.pendingImageDeletionRepository = pendingImageDeletionRepository;
        this.itemImageReferenceRepository = itemImageReferenceRepository;
    }

    public void queueItemImages(Long itemId, Long ownerId) {
        for (String url : itemImageReferenceRepository.findUrlsByItemId(itemId)) {
            queue(url, ownerId);
        }
    }

    public void queue(String url, Long ownerId) {
        if (url == null || url.isBlank() || pendingImageDeletionRepository.existsByUrl(url)) {
            return;
        }
        PendingImageDeletion pending = new PendingImageDeletion();
        pending.setUrl(url);
        pending.setOwnerId(ownerId);
        pending.setRequestedAt(new Date());
        pendingImageDeletionRepository.save(pending);
    }
}
