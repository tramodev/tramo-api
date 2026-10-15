// Copyright (C) 2026 Ezequiel Martino
// SPDX-License-Identifier: AGPL-3.0-only
package com.tramo.backend.user;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.auth.repository.EmailVerificationTokenRepository;
import com.tramo.backend.comment.repository.CommentRepository;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.repository.ProjectBookmarkRepository;
import com.tramo.backend.project.repository.ProjectVoteRepository;
import com.tramo.backend.subscription.patreon.PatreonConnectToken;
import com.tramo.backend.subscription.patreon.PatreonConnectTokenRepository;
import com.tramo.backend.subscription.service.SubscriptionService;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.BlockedUserRepository;
import com.tramo.backend.user.repository.FollowRepository;
import com.tramo.backend.user.repository.UserBadgeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountDeletionTest extends AbstractIntegrationTest {

    @Autowired
    private ProjectVoteRepository projectVoteRepository;
    @Autowired
    private ProjectBookmarkRepository projectBookmarkRepository;
    @Autowired
    private UserBadgeRepository userBadgeRepository;
    @Autowired
    private FollowRepository followRepository;
    @Autowired
    private BlockedUserRepository blockedUserRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private SubscriptionService subscriptionService;
    @Autowired
    private PatreonConnectTokenRepository patreonConnectTokenRepository;
    @MockitoSpyBean
    private EmailVerificationTokenRepository emailVerificationTokenRepository;

    private Project published(User owner, String title) {
        return createProject(owner, title, "published", "A description", null);
    }

    private void action(User user, String url) throws Exception {
        mockMvc.perform(post(url).header("Authorization", bearer(user))).andExpect(status().isOk());
    }

    private void deleteAccount(User user) throws Exception {
        mockMvc.perform(delete("/user/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());
    }

    private PatreonConnectToken pendingConnection(User user) {
        PatreonConnectToken token = new PatreonConnectToken();
        token.setUser(user);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(Instant.now().plusSeconds(900));
        return patreonConnectTokenRepository.saveAndFlush(token);
    }

    @Test
    void deletingAccountRemovesSubscriptionHistoryAndPaymentLinks() throws Exception {
        User me = createUser("delsubscriber");
        User other = createUser("keepsubscriber");
        Project mine = published(me, "Subscriber project");
        var plan = subscriptionService.findOrCreateSupporterPlan();
        subscriptionService.activateSupporterSubscription(me, plan);
        subscriptionService.cancel(me);
        subscriptionService.activateSupporterSubscription(me, plan);
        subscriptionService.activateSupporterSubscription(other, plan);
        Long paymentId = jdbcTemplate.queryForObject("INSERT INTO payment (id, amount, currency) "
                + "VALUES (nextval('payment_seq'), 5, 'USD') RETURNING id", Long.class);
        jdbcTemplate.update("INSERT INTO subscription_payment (subscription_id, payment_id) "
                + "SELECT id, ? FROM subscription WHERE user_id = ? AND status = 'CANCELED'", paymentId, me.getId());

        deleteAccount(me);

        assertThat(userRepository.findById(me.getId())).isEmpty();
        assertThat(projectRepository.findById(mine.getId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscription WHERE user_id = ?", Long.class, me.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subscription_payment WHERE payment_id = ?", Long.class, paymentId)).isZero();
        assertThat(subscriptionService.isSupporter(other)).isTrue();
    }

    @Test
    void deletingAccountRemovesPendingPatreonConnections() throws Exception {
        User me = createUser("delpendingpatreon");
        User other = createUser("keeppendingpatreon");
        published(me, "Pending connection project");
        PatreonConnectToken mine = pendingConnection(me);
        PatreonConnectToken theirs = pendingConnection(other);

        deleteAccount(me);

        assertThat(userRepository.findById(me.getId())).isEmpty();
        assertThat(patreonConnectTokenRepository.findById(mine.getId())).isEmpty();
        assertThat(patreonConnectTokenRepository.findById(theirs.getId())).isPresent();
    }

    @Test
    void failedAccountDeletionRollsBackProjectsAndRelatedRecords() throws Exception {
        User me = createUser("delrollback");
        Project first = published(me, "Keep first");
        Project second = published(me, "Keep second");
        String imageUrl = "https://test-bucket.example.com/thumbnail/" + me.getId() + "/rollback.jpg";
        first.setThumbnailImageUrl(imageUrl);
        projectRepository.saveAndFlush(first);
        long trailId = postForId(me, "/api/project/" + pid(first) + "/trail", "{\"title\":\"Keep trail\"}");
        long itemId = postForId(me, "/api/trail/" + trailId + "/item", "{\"title\":\"Keep note\"}");
        subscriptionService.activateSupporterSubscription(me, subscriptionService.findOrCreateSupporterPlan());
        PatreonConnectToken token = pendingConnection(me);
        doThrow(new IllegalStateException("injected failure")).when(emailVerificationTokenRepository).deleteByUserId(me.getId());

        mockMvc.perform(delete("/user/me").header("Authorization", bearer(me)))
                .andExpect(status().isInternalServerError());

        assertThat(userRepository.findById(me.getId())).isPresent();
        assertThat(projectRepository.findByOwnerId(me.getId())).extracting(Project::getId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(subscriptionService.isSupporter(me)).isTrue();
        assertThat(patreonConnectTokenRepository.findById(token.getId())).isPresent();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pending_image_deletion WHERE url = ?", Long.class, imageUrl)).isZero();
        mockMvc.perform(get("/api/trail/" + trailId + "/item").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(itemId));
    }

    @Test
    void deleteAccountRequiresAuthentication() throws Exception {
        mockMvc.perform(delete("/user/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void deletingAccountRemovesTheUser() throws Exception {
        User me = createUser("delme1");
        Long id = me.getId();

        deleteAccount(me);

        assertThat(userRepository.findById(id)).isEmpty();
    }

    @Test
    void deletingAccountRemovesOwnProjects() throws Exception {
        User me = createUser("delme2");
        published(me, "Doomed one");
        published(me, "Doomed two");

        deleteAccount(me);

        assertThat(projectRepository.findByOwnerId(me.getId())).isEmpty();
    }

    @Test
    void deletingAccountRemovesVotesBookmarksAndBadges() throws Exception {
        User me = createUser("delme3");
        User other = createUser("delother3");
        Project theirs = published(other, "Theirs");
        action(me, "/api/project/" + pid(theirs) + "/vote");
        action(me, "/api/project/" + pid(theirs) + "/bookmark");
        mockMvc.perform(get("/api/profile/stats").header("Authorization", bearer(me)))
                .andExpect(status().isOk());

        assertThat(projectVoteRepository.findByProjectIdAndUserId(theirs.getId(), me.getId())).isPresent();

        deleteAccount(me);

        assertThat(projectVoteRepository.findByProjectIdAndUserId(theirs.getId(), me.getId())).isEmpty();
        assertThat(projectBookmarkRepository.findByProjectIdAndUserId(theirs.getId(), me.getId())).isEmpty();
        assertThat(userBadgeRepository.findByUserId(me.getId())).isEmpty();
    }

    @Test
    void deletingAccountRemovesFollowsInBothDirections() throws Exception {
        User me = createUser("delme4");
        User followed = createUser("delfollowed4");
        User follower = createUser("delfollower4");
        action(me, "/api/users/followed4x/follow".replace("followed4x", followed.getUsername()));
        action(follower, "/api/users/" + me.getUsername() + "/follow");

        assertThat(followRepository.countByFollowedId(followed.getId())).isEqualTo(1);
        assertThat(followRepository.countByFollowedId(me.getId())).isEqualTo(1);

        deleteAccount(me);

        assertThat(followRepository.countByFollowedId(followed.getId())).isZero();
        assertThat(followRepository.countByFollowedId(me.getId())).isZero();
        assertThat(followRepository.countByFollowerId(me.getId())).isZero();
    }

    @Test
    void deletingAccountRemovesBlocksInBothDirections() throws Exception {
        User me = createUser("delme5");
        User blocked = createUser("delblocked5");
        User blocker = createUser("delblocker5");
        action(me, "/api/users/" + blocked.getUsername() + "/block");
        action(blocker, "/api/users/" + me.getUsername() + "/block");

        assertThat(blockedUserRepository.existsEitherDirection(me.getId(), blocked.getId())).isTrue();
        assertThat(blockedUserRepository.existsEitherDirection(blocker.getId(), me.getId())).isTrue();

        deleteAccount(me);

        assertThat(blockedUserRepository.existsEitherDirection(me.getId(), blocked.getId())).isFalse();
        assertThat(blockedUserRepository.existsEitherDirection(blocker.getId(), me.getId())).isFalse();
    }

    @Test
    void deletingAccountSoftDeletesCommentsKeepingTheThread() throws Exception {
        User me = createUser("delme6");
        User other = createUser("delother6");
        Project theirs = published(other, "Discussed");
        mockMvc.perform(post("/api/project/" + pid(theirs) + "/comments")
                        .header("Authorization", bearer(me))
                        .contentType(APPLICATION_JSON)
                        .content("{\"content\":\"my two cents\"}"))
                .andExpect(status().isOk());

        assertThat(commentRepository.findIdsByProjectId(theirs.getId())).hasSize(1);

        deleteAccount(me);

        assertThat(commentRepository.findIdsByProjectId(theirs.getId())).hasSize(1);
        mockMvc.perform(get("/api/public/project/" + pid(theirs) + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].deleted").value(true));
    }

    @Test
    void deletingAccountKeepsForksMadeByOthers() throws Exception {
        User me = createUser("delme7");
        User forker = createUser("delforker7");
        Project mine = published(me, "Forked source");
        String forkId = postForProjectId(forker, "/api/project/" + pid(mine) + "/fork", "");

        deleteAccount(me);

        mockMvc.perform(get("/api/project/" + forkId).header("Authorization", bearer(forker)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forkedFromId").doesNotExist());
    }

    @Test
    void deletingAccountRevokesRefreshTokens() throws Exception {
        User me = createUser("delme8");
        String login = mockMvc.perform(post("/api/auth/login")
                        .with(uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"delme8\",\"password\":\"Passw0rd123!\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String refreshToken = com.jayway.jsonpath.JsonPath.read(login, "$.refreshToken");

        deleteAccount(me);

        mockMvc.perform(post("/api/auth/refresh")
                        .with(uniqueIp())
                        .contentType(APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void deletedUserDisappearsFromPublicProfile() throws Exception {
        User me = createUser("delme9");
        published(me, "Public one");

        mockMvc.perform(get("/api/public/users/delme9")).andExpect(status().isOk());

        deleteAccount(me);

        mockMvc.perform(get("/api/public/users/delme9")).andExpect(status().isNotFound());
    }
}
