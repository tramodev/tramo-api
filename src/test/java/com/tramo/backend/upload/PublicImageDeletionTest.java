package com.tramo.backend.upload;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectSnapshot;
import com.tramo.backend.project.repository.ProjectSnapshotRepository;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.upload.entity.PendingImageDeletion;
import com.tramo.backend.upload.repository.PendingImageDeletionRepository;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;

import java.time.Duration;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicImageDeletionTest extends AbstractIntegrationTest {
    @Autowired ImageDeletionQueue queue;
    @Autowired PendingImageDeletionRepository pending;
    @Autowired ProjectSnapshotRepository snapshots;
    @Autowired ItemService items;
    @Autowired R2Client storage;

    private String url(User user, String kind) {
        return "https://test-bucket.example.com/" + kind + "/" + user.getId() + "/hash.jpg";
    }

    private void queueStale(String url, User user) {
        queue.queue(url, user.getId());
        jdbcTemplate.update("UPDATE pending_image_deletion SET requested_at = ? WHERE url = ?",
                new Date(System.currentTimeMillis() - Duration.ofDays(2).toMillis()), url);
    }

    private void content(long item, User user, String content) throws Exception {
        mockMvc.perform(put("/api/item/" + item + "/content").header("Authorization", bearer(user))
                        .contentType("application/json").content(contentRequest(lexicalText(content))))
                .andExpect(status().isNoContent());
    }

    @Test
    void neitherQueueNorStorageAcceptForeignOrNonCanonicalUrls() {
        User owner = createUser("urlowner");
        User attacker = createUser("urlattacker");
        for (String kind : new String[]{"editor-image", "avatar", "banner", "thumbnail"}) {
            String url = url(owner, kind);
            queue.queue(url, attacker.getId());
            assertThat(storage.deleteByPublicUrl(url, attacker.getId())).isFalse();
            assertThat(storage.isOwnedPublicUrl(url + "?query=1", owner.getId())).isFalse();
        }
        assertThat(storage.isOwnedPublicUrl(url(owner, "editor-image") + "/../other.jpg", owner.getId())).isFalse();
        assertThat(pending.count()).isZero();
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void corruptedLegacyQueueEntryCannotDeleteAnotherUsersImage() {
        User owner = createUser("legacyowner");
        User attacker = createUser("legacyattacker");
        PendingImageDeletion entry = new PendingImageDeletion();
        entry.setUrl(url(owner, "editor-image"));
        entry.setOwnerId(attacker.getId());
        entry.setRequestedAt(new Date(0));
        pending.save(entry);

        items.purgePendingImageDeletions();

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        assertThat(pending.count()).isZero();
    }

    @Test
    void foreignContentCannotEnqueueDeletionAndGlobalReferencesKeepImagesAlive() throws Exception {
        User owner = createUser("sharedimageowner");
        User reader = createUser("sharedimagereader");
        Project project = createProject(reader, "Uses image", "private");
        long item = postForId(reader, "/api/project/" + pid(project) + "/item", "{\"title\":\"Note\"}");
        String url = url(owner, "editor-image");
        content(item, reader, url);
        queueStale(url, owner);
        items.purgePendingImageDeletions();
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        assertThat(pending.existsByUrl(url)).isTrue();

        content(item, reader, "removed");
        items.purgePendingImageDeletions();
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
        assertThat(pending.existsByUrl(url)).isFalse();

        content(item, reader, url);
        content(item, reader, "removed again");
        assertThat(pending.existsByUrl(url)).isFalse();
    }

    @Test
    void snapshotsProtectLegacyImagesAcrossOwners() {
        User owner = createUser("snapshotimageowner");
        User reader = createUser("snapshotimagereader");
        String url = url(owner, "editor-image");
        ProjectSnapshot snapshot = new ProjectSnapshot();
        snapshot.setProject(createProject(reader, "Snapshot", "private"));
        snapshot.setTrigger("PUBLISH");
        snapshot.setVersion(1);
        snapshot.setContent("{\"image\":\"" + url + "\"}");
        snapshots.save(snapshot);
        queueStale(url, owner);

        items.purgePendingImageDeletions();

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        assertThat(pending.existsByUrl(url)).isTrue();
    }

    @Test
    void currentThumbnailsAvatarsAndBannersAreNotPurged() {
        User owner = createUser("publicimageowner");
        Project project = createProject(owner, "Thumbnail", "private");
        project.setThumbnailImageUrl(url(owner, "thumbnail"));
        projectRepository.save(project);
        owner.setImageUrl(url(owner, "avatar"));
        owner.setBannerUrl(url(owner, "banner"));
        userRepository.save(owner);
        for (String kind : new String[]{"thumbnail", "avatar", "banner"}) queueStale(url(owner, kind), owner);

        items.purgePendingImageDeletions();

        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
        assertThat(pending.count()).isEqualTo(3);
    }

    @Test
    void failedStorageDeletionRemainsQueuedForRetry() {
        User owner = createUser("retryimageowner");
        String url = url(owner, "editor-image");
        queueStale(url, owner);
        doThrow(new IllegalStateException("R2 unavailable")).doReturn(DeleteObjectResponse.builder().build())
                .when(s3Client).deleteObject(any(DeleteObjectRequest.class));

        items.purgePendingImageDeletions();
        assertThat(pending.existsByUrl(url)).isTrue();
        items.purgePendingImageDeletions();
        assertThat(pending.existsByUrl(url)).isFalse();
        verify(s3Client, times(2)).deleteObject(any(DeleteObjectRequest.class));
    }
}
