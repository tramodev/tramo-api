// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

import com.tramo.backend.common.ContentLimits;
import com.tramo.backend.project.entity.ProjectDraftCommit;
import com.tramo.backend.project.dto.DraftCommitDTO;
import com.tramo.backend.project.dto.DraftCommitRequest;
import com.tramo.backend.project.repository.ProjectDraftCommitRepository;
import com.tramo.backend.exception.RequestErrorCode;
import com.tramo.backend.exception.RequestValidationException;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.project.dto.StartProjectRequest;
import com.tramo.backend.project.dto.StartedProjectDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.trail.dto.ItemRequestDTO;
import com.tramo.backend.trail.dto.TrailRequestDTO;
import com.tramo.backend.trail.repository.ItemRepository;
import com.tramo.backend.trail.repository.TrailRepository;
import com.tramo.backend.trail.repository.TrailItemRepository;
import com.tramo.backend.trail.service.ItemService;
import com.tramo.backend.trail.service.TrailService;
import com.tramo.backend.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import com.tramo.backend.exception.ResourceNotFoundException;
import tools.jackson.databind.JsonNode;

@Service
public class ProjectStartService {
    private final ProjectDraftCommitRepository draftCommits;
    private final ProjectRepository projects;
    private final TrailRepository trails;
    private final TrailItemRepository steps;
    private final ItemRepository items;
    private final TrailService trailService;
    private final ItemService itemService;
    private final ProjectVersionCapture history;
    private final ProjectIdCodec codec;
    private final EntityManager entityManager;
    private final ObjectMapper mapper;

    public ProjectStartService(ProjectRepository projects, TrailRepository trails, TrailItemRepository steps,
                               ItemRepository items, TrailService trailService, ItemService itemService,
                               ProjectIdCodec codec, EntityManager entityManager, ObjectMapper mapper, ProjectVersionCapture history, ProjectDraftCommitRepository draftCommits) {
        this.draftCommits = draftCommits;
        this.projects = projects;
        this.trails = trails;
        this.steps = steps;
        this.items = items;
        this.trailService = trailService;
        this.itemService = itemService;
        this.history = history;
        this.codec = codec;
        this.entityManager = entityManager;
        this.mapper = mapper;
    }

    @Transactional
    public DraftCommitDTO commitDraft(DraftCommitRequest request, User user) {
        entityManager.find(User.class, user.getId(), LockModeType.PESSIMISTIC_WRITE);
        var existing = draftCommits.findByUserIdAndClientRequestId(user.getId(), request.clientRequestId());
        if (existing.isPresent()) return draftResult(existing.get());
        ContentLimits.assertNote(request.item().content());
        if (java.util.stream.Stream.of(request.project().title(), request.trail().title(), request.item().title()).noneMatch(this::visibleText)
                && !meaningfulContent(request.item().content())) throw new RequestValidationException(RequestErrorCode.EDITOR_CONTENT_INVALID);
        var project = new Project();
        project.setOwner(user);
        project.setTitle(draftTitle(request.project().title(), "Untitled project"));
        project.setVisibility(ProjectVisibility.PRIVATE);
        project.setCreationDate(new Date());
        project.setModifiedDate(new Date());
        projects.save(project);
        String trailTitle = draftTitle(request.trail().title(), "My first trail");
        String itemTitle = draftTitle(request.item().title(), "Untitled note");
        Long trailId = trail(project.getId(), trailTitle, "", user);
        var itemRequest = new ItemRequestDTO();
        itemRequest.setTitle(itemTitle);
        itemRequest.setTitleAlign(request.item().titleAlign());
        Long itemId = itemService.create(trailId, itemRequest, user).getId();
        itemService.update(itemId, itemRequest, user);
        itemService.updateContent(itemId, request.item().content(), user);
        var receipt = new ProjectDraftCommit();
        receipt.setUserId(user.getId());
        receipt.setClientRequestId(request.clientRequestId());
        receipt.setProjectId(project.getId());
        receipt.setTrailId(trailId);
        receipt.setItemId(itemId);
        receipt.setProjectTitle(project.getTitle());
        receipt.setTrailTitle(trailTitle);
        receipt.setItemTitle(itemTitle);
        draftCommits.save(receipt);
        return draftResult(receipt);
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeDraftCommits() {
        draftCommits.deleteByCreatedAtBefore(Date.from(Instant.now().minus(30, ChronoUnit.DAYS)));
    }

    private boolean visibleText(String text) {
        return text != null && text.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c) && Character.getType(c) != Character.FORMAT);
    }

    private boolean meaningfulContent(String content) {
        if (!visibleText(content)) return false;
        try { return meaningfulNode(mapper.readTree(content).path("root")); }
        catch (RuntimeException invalid) { return true; }
    }

    private boolean meaningfulNode(JsonNode node) {
        if (visibleText(node.path("text").asText("")) || visibleText(node.path("equation").asText(""))
                || visibleText(node.path("abc").asText(""))) return true;
        if ("image".equals(node.path("type").asText()) && visibleText(node.path("imageId").asText(""))) return true;
        if ("embed".equals(node.path("type").asText()) && visibleText(node.path("id").asText(""))) return true;
        for (JsonNode child : node.path("children")) if (meaningfulNode(child)) return true;
        return false;
    }

    private String draftTitle(String title, String fallback) {
        return title.isBlank() ? fallback : title.strip();
    }

    private DraftCommitDTO draftResult(ProjectDraftCommit receipt) {
        if (receipt.getProjectId() == null || receipt.getTrailId() == null || receipt.getItemId() == null)
            throw new ResourceNotFoundException("This draft's project or note was deleted.");
        return new DraftCommitDTO(codec.encode(receipt.getProjectId()), receipt.getTrailId(),
                receipt.getItemId(), 0, receipt.getProjectTitle(), receipt.getTrailTitle(), receipt.getItemTitle());
    }

    @Transactional
    public StartedProjectDTO create(StartProjectRequest request, User user) {
        entityManager.find(User.class, user.getId(), LockModeType.PESSIMISTIC_WRITE);
        var existing = projects.findByOwnerIdAndStartRequestId(user.getId(), request.requestId());
        if (existing.isPresent()) return start(existing.get().getId(), null, user);
        var project = new Project();
        project.setOwner(user);
        project.setStartRequestId(request.requestId());
        project.setTitle("Untitled project");
        project.setVisibility(ProjectVisibility.PRIVATE);
        project.setCreationDate(new Date());
        project.setModifiedDate(new Date());
        projects.save(project);
        return start(project.getId(), null, user);
    }

    @Transactional
    public StartedProjectDTO createExample(User user) {
        entityManager.find(User.class, user.getId(), LockModeType.PESSIMISTIC_WRITE);
        var existing = projects.findExampleByOwnerId(user.getId());
        if (existing.isPresent()) {
            var project = entityManager.find(Project.class, existing.get().getId(), LockModeType.PESSIMISTIC_WRITE);
            var firstTrail = trails.findFirstByProjectIdOrderByOrderIndexAscIdAsc(project.getId());
            Long trailId = firstTrail.isPresent() ? firstTrail.get().getId() : trail(project.getId(), "My first trail", "", user);
            var firstStep = steps.findFirstByTrailIdOrderByOrderIndexAscIdAsc(trailId);
            Long itemId = firstStep.isPresent() ? firstStep.get().getItem().getId() : note(trailId, "Untitled note", null, user);
            return result(project, trailId, itemId);
        }
        var project = new Project();
        project.setOwner(user);
        project.setExample(true);
        project.setTitle("Memex and Vannevar Bush");
        project.setDescription("Explore Bush’s vision of a personal knowledge library and the associative trails that connect its ideas. Two trails share the same Memex note.");
        project.setVisibility(ProjectVisibility.PRIVATE);
        project.setCreationDate(new Date());
        project.setModifiedDate(new Date());
        projects.save(project);
        return example(project, user);
    }

    @Transactional
    public StartedProjectDTO start(Long projectId, Long preferredTrailId, User user) {
        var project = history.lock(projectId, user);
        var existingTrails = trails.findByProjectId(projectId).stream().filter(t -> preferredTrailId == null || t.getId().equals(preferredTrailId)).toList();
        if (preferredTrailId != null && existingTrails.isEmpty()) throw new RequestValidationException(RequestErrorCode.PROJECT_TRAIL_INVALID);
        for (var trail : existingTrails) {
            var trailSteps = steps.findByTrailIdOrderByOrderIndexAsc(trail.getId());
            if (!trailSteps.isEmpty()) return result(project, trail.getId(), trailSteps.get(0).getItem().getId());
        }
        var existingItems = items.findByProjectId(projectId);
        if (preferredTrailId == null && !existingItems.isEmpty()) return result(project, null, existingItems.get(0).getId());
        Long trailId = existingTrails.isEmpty() ? trail(projectId, "My first trail", "", user) : existingTrails.get(0).getId();
        return result(project, trailId, note(trailId, "Untitled note", null, user));
    }

    private StartedProjectDTO example(Project project, User user) {
        Long vision = trail(project.getId(), "Bush’s vision", "Meet Vannevar Bush, read the idea behind his essay, and explore the Memex.", user);
        Long connections = trail(project.getId(), "Thinking in trails", "Explore how the Memex connects records into reusable paths of thought.", user);
        Long bush = note(vision, "Vannevar Bush", null, user);
        Long essay = note(vision, "As We May Think", "Published in The Atlantic in July 1945, As We May Think asks how tools could help people consult and connect the records they collect. Bush contrasts rigid indexing with the way thought moves by association.\nHis proposed answer includes the Memex: a personal library where a reader could build lasting connections between records. Read the original: https://www.w3.org/History/1945/vbush/", user);
        mention(bush, "Vannevar Bush was an American engineer and science administrator. In 1945, he published ", "As We May Think", ", an essay about how people might use technology to work with a growing body of knowledge.", "Rather than only storing more information, his proposal focused on helping a reader find, connect and revisit ideas.", essay, user);
        Long memex = note(vision, "Memex", "The Memex was a proposed device for storing and consulting a person’s books, records and communications. Bush imagined a desk with screens and microfilm, rather than a modern computer or the web.\nIts distinctive idea was associative access: a reader could connect records and follow those connections later. The same record could belong to several trails. The Memex described in the essay was a proposal, not a finished product.", user);
        itemService.attachToTrail(connections, memex, user);
        note(connections, "Associative trails", "An associative trail is a named path through connected records. In Bush’s proposal, a reader could join records, add comments and return to the path without reconstructing every connection.\nA record could appear in more than one trail. That lets one source support different explanations while each trail keeps its own context and order.", user, memex, "Memex");
        note(connections, "Sharing a trail", "Bush imagined readers copying trails for other people to explore and extend in their own Memex. Sharing meant passing along a path through material, not just an isolated document.\nTry that idea here: edit the shared Memex note and open Bush’s vision. Both trails use the same note while keeping independent reading orders. Type @ in a note to link another note.", user);
        return result(project, vision, bush);
    }

    private Long trail(Long projectId, String title, String description, User user) {
        var request = new TrailRequestDTO();
        request.setTitle(title);
        request.setDescription(description);
        request.setVisibility("private");
        return trailService.create(projectId, request, user).getId();
    }

    private Long note(Long trailId, String title, String text, User user) {
        return note(trailId, title, text, user, null, null);
    }

    private Long note(Long trailId, String title, String text, User user, Long linkedId, String linkedTitle) {
        var request = new ItemRequestDTO();
        request.setTitle(title);
        Long id = itemService.create(trailId, request, user).getId();
        if (text != null) {
            var paragraphs = new ArrayList<Map<String, Object>>();
            text.lines().forEach(line -> paragraphs.add(Map.of("type", "paragraph", "version", 1, "children", List.of(textNode(line)))));
            if (linkedId != null) paragraphs.add(Map.of("type", "paragraph", "version", 1, "children", List.of(
                    textNode("Related note: "),
                    linkNode(linkedId, linkedTitle))));
            itemService.updateContent(id, mapper.writeValueAsString(Map.of("root", Map.of("type", "root", "version", 1, "children", paragraphs))), user);
        }
        return id;
    }

    private void mention(Long sourceId, String before, String title, String after, String continuation, Long targetId, User user) {
        var paragraphs = List.of(
                Map.of("type", "paragraph", "version", 1, "children", List.of(textNode(before), linkNode(targetId, title), textNode(after))),
                Map.of("type", "paragraph", "version", 1, "children", List.of(textNode(continuation))));
        itemService.updateContent(sourceId, mapper.writeValueAsString(Map.of("root", Map.of("type", "root", "version", 1, "children", paragraphs))), user);
    }

    private Map<String, Object> linkNode(Long id, String title) {
        return Map.of("type", "link", "version", 1, "url", "#", "rel", "tramo-idea:" + id, "children", List.of(textNode(title)));
    }

    private Map<String, Object> textNode(String text) {
        return Map.of("type", "text", "version", 1, "text", text, "format", 0, "mode", "normal", "style", "", "detail", 0);
    }

    private StartedProjectDTO result(Project project, Long trailId, Long itemId) {
        return new StartedProjectDTO(codec.encode(project.getId()), trailId, itemId);
    }
}
