package com.tramo.backend.tag.service;

import com.tramo.backend.exception.LimitExceededException;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.security.ratelimit.RateLimiterService;
import com.tramo.backend.tag.dto.TagDTO;
import com.tramo.backend.tag.entity.Tag;
import com.tramo.backend.tag.repository.TagRepository;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class TagService {

    private final TagRepository tagRepository;
    private final TagCache tagCache;
    private final RateLimiterService rateLimiterService;
    private final int visibilityThreshold;
    private final int rateLimitCapacity;
    private final int rateLimitRefillTokens;
    private final Duration rateLimitRefillDuration;

    public TagService(TagRepository tagRepository,
                       TagCache tagCache,
                       RateLimiterService rateLimiterService,
                       @Value("${app.tags.visibility-threshold:3}") int visibilityThreshold,
                       @Value("${app.tags.rate-limit.capacity:5}") int rateLimitCapacity,
                       @Value("${app.tags.rate-limit.refill-tokens:5}") int rateLimitRefillTokens,
                       @Value("${app.tags.rate-limit.refill-duration-minutes:10}") long rateLimitRefillMinutes) {
        this.tagRepository = tagRepository;
        this.tagCache = tagCache;
        this.rateLimiterService = rateLimiterService;
        this.visibilityThreshold = visibilityThreshold;
        this.rateLimitCapacity = rateLimitCapacity;
        this.rateLimitRefillTokens = rateLimitRefillTokens;
        this.rateLimitRefillDuration = Duration.ofMinutes(rateLimitRefillMinutes);
    }

    private boolean isVisible(Tag tag) {
        return tag.isOfficial() || tag.getUsageCount() >= visibilityThreshold;
    }

    
    public List<TagDTO> autocomplete(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase();
        return tagCache.all().stream()
                .filter(this::isVisible)
                .filter(tag -> q.isEmpty() || tag.getName().contains(q))
                .sorted(Comparator.comparing(Tag::isOfficial).reversed()
                        .thenComparing(Comparator.comparingLong(Tag::getUsageCount).reversed()))
                .limit(limit)
                .map(tag -> new TagDTO(tag.getName(), tag.isOfficial(), tag.getUsageCount()))
                .toList();
    }

    
    public List<TagDTO> hotTopics(int limit) {
        return tagCache.all().stream()
                .filter(this::isVisible)
                .sorted(Comparator.comparingLong(Tag::getUsageCount).reversed())
                .limit(limit)
                .map(tag -> new TagDTO(tag.getName(), tag.isOfficial(), tag.getUsageCount()))
                .toList();
    }

    private static String normalize(String rawName) {
        return rawName == null ? "" : rawName.trim().toLowerCase();
    }

    






    private Tag resolveOrCreate(String rawName, Long userId) {
        String name = normalize(rawName);
        Tag existing = tagRepository.findByName(name).orElse(null);
        if (existing != null) {
            return existing;
        }

        if (userId != null) {
            Bucket bucket = rateLimiterService.resolveBucket("tag-create:" + userId,
                    rateLimitCapacity, rateLimitRefillTokens, rateLimitRefillDuration);
            if (!bucket.tryConsume(1)) {
                throw new LimitExceededException("You're creating tags too fast. Try again later.");
            }
        }

        try {
            Tag created = tagRepository.save(new Tag(name, false, userId));
            tagCache.invalidate();
            return created;
        } catch (DataIntegrityViolationException e) {
            
            return tagRepository.findByName(name).orElseThrow(() -> e);
        }
    }

    



    @Transactional
    public void applyProjectTags(Project project, List<String> rawNames, Long userId) {
        Set<String> targetNames = new HashSet<>();
        for (String rawName : rawNames) {
            String name = normalize(rawName);
            if (!name.isEmpty()) {
                targetNames.add(name);
            }
        }

        Set<Tag> current = project.getProjectTags();

        List<Tag> toRemove = current.stream()
                .filter(tag -> !targetNames.contains(tag.getName()))
                .toList();
        current.removeAll(toRemove);
        if (!toRemove.isEmpty()) {
            tagRepository.decrementUsageCount(toRemove.stream().map(Tag::getId).toList());
        }

        Set<String> currentNames = current.stream().map(Tag::getName).collect(java.util.stream.Collectors.toSet());
        List<Long> addedIds = new java.util.ArrayList<>();
        for (String name : targetNames) {
            if (!currentNames.contains(name)) {
                Tag tag = resolveOrCreate(name, userId);
                current.add(tag);
                addedIds.add(tag.getId());
            }
        }
        if (!addedIds.isEmpty()) {
            tagRepository.incrementUsageCount(addedIds);
        }

        if (!toRemove.isEmpty() || !currentNames.containsAll(targetNames)) {
            tagCache.invalidate();
        }
    }
}
