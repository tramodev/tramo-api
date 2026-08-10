package com.tramo.backend.user;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.comment.repository.CommentRepository;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.repository.ProjectBookmarkRepository;
import com.tramo.backend.project.repository.ProjectVoteRepository;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.BlockedUserRepository;
import com.tramo.backend.user.repository.FollowRepository;
import com.tramo.backend.user.repository.UserBadgeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
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
