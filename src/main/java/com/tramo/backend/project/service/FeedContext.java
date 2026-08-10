package com.tramo.backend.project.service;

import com.tramo.backend.project.entity.ProjectSnapshot;

import java.util.List;
import java.util.Map;
import java.util.Set;

record FeedContext(Map<Long, Long> voteCounts, Map<Long, Long> forkCounts, Map<Long, Long> commentCounts,
                    Set<Long> votedProjectIds, Set<Long> bookmarkedProjectIds,
                    Map<Long, ProjectSnapshot> latestPublishByProjectId,
                    Map<Long, List<String>> tagNamesByProjectId,
                    Map<Long, ThumbnailResolution> thumbnailByProjectId) {
    static final FeedContext EMPTY = new FeedContext(Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Map.of(), Map.of(), Map.of());

    FeedContext withThumbnails(Map<Long, ThumbnailResolution> thumbnails) {
        return new FeedContext(voteCounts, forkCounts, commentCounts, votedProjectIds, bookmarkedProjectIds,
                latestPublishByProjectId, tagNamesByProjectId, thumbnails);
    }

    FeedContext withCommentCounts(Map<Long, Long> counts) {
        return new FeedContext(voteCounts, forkCounts, counts, votedProjectIds, bookmarkedProjectIds,
                latestPublishByProjectId, tagNamesByProjectId, thumbnailByProjectId);
    }

    FeedContext withLatestPublish(Map<Long, ProjectSnapshot> latestPublish) {
        return new FeedContext(voteCounts, forkCounts, commentCounts, votedProjectIds, bookmarkedProjectIds,
                latestPublish, tagNamesByProjectId, thumbnailByProjectId);
    }
}
