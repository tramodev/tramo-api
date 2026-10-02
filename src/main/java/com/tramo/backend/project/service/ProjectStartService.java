package com.tramo.backend.project.service;

import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.project.dto.StartProjectRequest;
import com.tramo.backend.project.dto.StartedProjectDTO;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.entity.ProjectVisibility;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.trail.dto.ItemRequestDTO;
import com.tramo.backend.trail.dto.TrailRequestDTO;
import com.tramo.backend.trail.entity.AssociationTargetType;
import com.tramo.backend.trail.entity.AssociationType;
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
        project.setTitle(request.example() ? "API foundations" : "Untitled project");
        project.setVisibility(ProjectVisibility.PRIVATE);
        project.setCreationDate(new Date());
        project.setModifiedDate(new Date());
        if (request.example()) project.setDescription("A short introduction to HTTP and token authentication. Follow either trail and see how one note can support both explanations.");
        projects.save(project);
        return request.example() ? example(project, user) : start(project.getId(), null, user);
    }

    @Transactional
    public StartedProjectDTO start(Long projectId, Long preferredTrailId, User user) {
        access.getOwnedProject(projectId, user);
        var project = entityManager.find(Project.class, projectId, LockModeType.PESSIMISTIC_WRITE);
        var existingTrails = trails.findByProjectId(projectId).stream().filter(t -> preferredTrailId == null || t.getId().equals(preferredTrailId)).sorted(Comparator.comparing(t -> t.getId())).toList();
        if (preferredTrailId != null && existingTrails.isEmpty()) throw new IllegalArgumentException("Trail does not belong to this project");
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
        Long basics = trail(project.getId(), "API basics", "Start with HTTP, then follow a request and identify the caller.", user);
        Long tokens = trail(project.getId(), "Token authentication", "Follow authentication from credentials to tokens and JWTs.", user);
        Long http = note(basics, "HTTP", "HTTP is a protocol for exchanging messages between a client and a server. A web API uses it to expose data or actions through URLs.\nA method describes the action: GET reads a resource; POST commonly submits data. A status code describes the result, such as 200 for success or 404 when a resource was not found.", user);
        Long messages = note(basics, "Requests and responses", "A request includes a method, a URL, headers and sometimes a body. For example, GET /books asks the server for a collection of books.\nThe server returns a response with a status code, headers and an optional body. Many APIs use JSON for that body. Read the status before deciding how to handle the returned data.", user);
        Long auth = note(basics, "Authentication", "Authentication establishes who is making a request. Authorization decides what that caller may do. These are separate checks.\nAn API may verify credentials during sign-in and issue a token for later requests. Protected endpoints still need to check that the caller has permission to access each resource.", user);
        itemService.attachToTrail(tokens, auth, user);
        Long token = note(tokens, "Tokens", "A token represents a credential that a client presents to an API. A bearer token is normally sent in the Authorization header over HTTPS. Anyone who obtains it may be able to use it.\nTokens can expire and may be opaque strings or structured values. Keep them out of public links and logs. The server must validate them before trusting a request.", user);
        Long jwt = note(tokens, "JWT", "A JSON Web Token contains a header, a payload of claims and a signature. A signed JWT is not encrypted: its payload can normally be read by anyone who has it.\nAn API must verify the signature and relevant claims, including expiration and the expected issuer and audience. JWT is one possible token format, not a replacement for authorization checks.", user);
        itemService.tie(messages, AssociationType.REQUIRES, AssociationTargetType.ITEM, http, user);
        itemService.tie(jwt, AssociationType.REQUIRES, AssociationTargetType.ITEM, token, user);
        itemService.updateStep(basics, messages, "HTTP defines the exchange. Next, look at what travels in each direction.", null, user);
        itemService.updateStep(tokens, token, "Once a caller has authenticated, a token can carry their credential into later requests.", null, user);
        return result(project, basics, http);
    }

    private Long trail(Long projectId, String title, String description, User user) {
        var request = new TrailRequestDTO();
        request.setTitle(title);
        request.setDescription(description);
        request.setVisibility("private");
        return trailService.create(projectId, request, user).getId();
    }

    private Long note(Long trailId, String title, String text, User user) {
        var request = new ItemRequestDTO();
        request.setTitle(title);
        Long id = itemService.create(trailId, request, user).getId();
        if (text != null) {
            var paragraphs = text.lines().map(line -> Map.of("type", "paragraph", "version", 1, "children", List.of(Map.of("type", "text", "version", 1, "text", line, "format", 0, "mode", "normal", "style", "", "detail", 0)))).toList();
            itemService.updateContent(id, mapper.writeValueAsString(Map.of("root", Map.of("type", "root", "version", 1, "children", paragraphs))), user);
        }
        return id;
    }

    private StartedProjectDTO result(Project project, Long trailId, Long itemId) {
        return new StartedProjectDTO(codec.encode(project.getId()), trailId, itemId);
    }
}
