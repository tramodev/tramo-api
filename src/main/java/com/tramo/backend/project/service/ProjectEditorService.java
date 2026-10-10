package com.tramo.backend.project.service;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.project.dto.EditorBootstrapDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.trail.dto.ItemTextStatsDTO;
import com.tramo.backend.trail.dto.TrailItemContentDTO;
import com.tramo.backend.trail.entity.Item;
import com.tramo.backend.trail.entity.ItemContent;
import com.tramo.backend.trail.entity.Trail;
import com.tramo.backend.trail.entity.TrailItem;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.user.entity.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProjectEditorService {
    private final AccessGuard accessGuard;
    private final ProjectIdCodec projectIdCodec;
    private final ProjectResponseMapper responseMapper;
    private final TrailRepository trailRepository;
    private final TrailItemRepository trailItemRepository;
    private final ItemRepository itemRepository;
    private final ItemService itemService;

    public ProjectEditorService(AccessGuard accessGuard, ProjectIdCodec projectIdCodec,
            ProjectResponseMapper responseMapper, TrailRepository trailRepository,
            TrailItemRepository trailItemRepository, ItemRepository itemRepository, ItemService itemService) {
        this.accessGuard = accessGuard;
        this.projectIdCodec = projectIdCodec;
        this.responseMapper = responseMapper;
        this.trailRepository = trailRepository;
        this.trailItemRepository = trailItemRepository;
        this.itemRepository = itemRepository;
        this.itemService = itemService;
    }

    @Transactional(readOnly = true)
    public EditorBootstrapDTO get(Long projectId, Long preferredItemId, Long preferredTrailId, User requester) {
        Project project = accessGuard.getOwnedProject(projectId, requester);
        List<Trail> trails = trailRepository.findByProjectId(projectId);
        List<TrailItem> steps = trailItemRepository.findEditorSteps(projectId);
        Map<Long, List<Long>> itemIdsByTrail = steps.stream().collect(Collectors.groupingBy(
                step -> step.getTrail().getId(), LinkedHashMap::new,
                Collectors.mapping(step -> step.getItem().getId(), Collectors.toList())));
        Map<Long, Item> items = new LinkedHashMap<>();
        for (TrailItem step : steps) items.putIfAbsent(step.getItem().getId(), step.getItem());
        for (Item item : itemRepository.findEditorItems(projectId)) items.putIfAbsent(item.getId(), item);
        Map<Long, ItemTextStatsDTO> stats = itemRepository.findTextStatsByProjectId(projectId).stream()
                .collect(Collectors.toMap(ItemTextStatsDTO::id, stat -> stat));

        Long selectedItemId = items.containsKey(preferredItemId) ? preferredItemId : null;
        Long selectedTrailId = null;
        if (selectedItemId != null) {
            if (preferredTrailId != null && itemIdsByTrail.getOrDefault(preferredTrailId, List.of()).contains(selectedItemId)) {
                selectedTrailId = preferredTrailId;
            } else {
                for (Trail trail : trails) {
                    if (itemIdsByTrail.getOrDefault(trail.getId(), List.of()).contains(selectedItemId)) {
                        selectedTrailId = trail.getId();
                        break;
                    }
                }
            }
        } else {
            for (Trail trail : trails) {
                List<Long> trailItems = itemIdsByTrail.getOrDefault(trail.getId(), List.of());
                if (!trailItems.isEmpty()) {
                    selectedTrailId = trail.getId();
                    selectedItemId = trailItems.get(0);
                    break;
                }
            }
            if (selectedItemId == null && !items.isEmpty()) selectedItemId = items.keySet().iterator().next();
        }

        List<TrailItemContentDTO> contents = List.of();
        if (selectedTrailId != null) {
            contents = itemService.getContentsForTrail(selectedTrailId, requester);
        } else if (selectedItemId != null) {
            Item item = itemRepository.findByIdWithContent(selectedItemId).orElseThrow();
            ItemContent content = item.getContent();
            contents = List.of(new TrailItemContentDTO(item.getId(),
                    content == null ? "" : content.getContent(), content == null ? 0 : content.getExtractionEpoch()));
        }

        List<EditorBootstrapDTO.EditorTrailDTO> trailDtos = trails.stream().map(trail ->
                new EditorBootstrapDTO.EditorTrailDTO(trail.getId(), trail.getTitle(), trail.getDescription(),
                        trail.getVersion(), trail.getForkedFrom() == null ? null : trail.getForkedFrom().getId(),
                        itemIdsByTrail.getOrDefault(trail.getId(), List.of()))).toList();
        List<EditorBootstrapDTO.EditorItemDTO> itemDtos = items.values().stream().map(item -> {
            ItemTextStatsDTO stat = stats.get(item.getId());
            return new EditorBootstrapDTO.EditorItemDTO(item.getId(), item.getTitle(), item.getTitleAlign(),
                    Boolean.TRUE.equals(item.getUnfiled()), stat == null ? 0 : stat.words(),
                    stat == null ? 0 : stat.characters());
        }).toList();
        return new EditorBootstrapDTO(projectIdCodec.encode(projectId), project.getTitle(),
                project.getDescription(), project.getGraphColors(), project.getVisibility(),
                responseMapper.resolveTagNames(project), trailDtos, itemDtos,
                selectedItemId, selectedTrailId, contents, requester.getUsername(), requester.getImageUrl());
    }
}
