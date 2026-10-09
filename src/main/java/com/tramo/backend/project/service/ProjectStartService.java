// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.project.service;

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
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class ProjectStartService {
    private final ProjectRepository projects;
    private final TrailRepository trails;
    private final TrailItemRepository steps;
    private final ItemRepository items;
    private final TrailService trailService;
    private final ItemService itemService;
    private final AccessGuard access;
    private final ProjectIdCodec codec;
    private final EntityManager entityManager;
    private final ObjectMapper mapper;

    public ProjectStartService(ProjectRepository projects, TrailRepository trails, TrailItemRepository steps,
                               ItemRepository items, TrailService trailService, ItemService itemService,
                               AccessGuard access, ProjectIdCodec codec, EntityManager entityManager, ObjectMapper mapper) {
        this.projects = projects;
        this.trails = trails;
        this.steps = steps;
        this.items = items;
        this.trailService = trailService;
        this.itemService = itemService;
        this.access = access;
        this.codec = codec;
        this.entityManager = entityManager;
        this.mapper = mapper;
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
            var firstTrail = trails.findFirstByProjectIdOrderByIdAsc(project.getId());
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
        access.getOwnedProject(projectId, user);
        var project = entityManager.find(Project.class, projectId, LockModeType.PESSIMISTIC_WRITE);
        var existingTrails = trails.findByProjectId(projectId).stream().filter(t -> preferredTrailId == null || t.getId().equals(preferredTrailId)).sorted(Comparator.comparing(t -> t.getId())).toList();
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
        Long bush = note(vision, "Vannevar Bush", "Vannevar Bush was an American engineer and science administrator. In 1945, he published As We May Think, an essay about how people might use technology to work with a growing body of knowledge.\nRather than only storing more information, his proposal focused on helping a reader find, connect and revisit ideas.", user);
        note(vision, "As We May Think", "Published in The Atlantic in July 1945, As We May Think asks how tools could help people consult and connect the records they collect. Bush contrasts rigid indexing with the way thought moves by association.\nHis proposed answer includes the Memex: a personal library where a reader could build lasting connections between records. Read the original: https://www.w3.org/History/1945/vbush/", user, bush, "Vannevar Bush");
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
                    Map.of("type", "link", "version", 1, "url", "#", "rel", "tramo-idea:" + linkedId, "children", List.of(textNode(linkedTitle))))));
            itemService.updateContent(id, mapper.writeValueAsString(Map.of("root", Map.of("type", "root", "version", 1, "children", paragraphs))), user);
        }
        return id;
    }

    private Map<String, Object> textNode(String text) {
        return Map.of("type", "text", "version", 1, "text", text, "format", 0, "mode", "normal", "style", "", "detail", 0);
    }

    private StartedProjectDTO result(Project project, Long trailId, Long itemId) {
        return new StartedProjectDTO(codec.encode(project.getId()), trailId, itemId);
    }
}
