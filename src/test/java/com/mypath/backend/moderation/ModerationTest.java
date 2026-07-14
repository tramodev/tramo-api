package com.mypath.backend.moderation;

import com.mypath.backend.AbstractIntegrationTest;
import com.mypath.backend.moderation.entity.ModerationLog;
import com.mypath.backend.moderation.entity.ProjectReport;
import com.mypath.backend.moderation.repository.ModerationLogRepository;
import com.mypath.backend.moderation.repository.ProjectReportRepository;
import com.mypath.backend.project.entity.Project;
import com.mypath.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModerationTest extends AbstractIntegrationTest {

    @Autowired
    ProjectReportRepository projectReportRepository;

    @Autowired
    ModerationLogRepository moderationLogRepository;

    @Test
    void adminEndpointsRejectAnonymous() throws Exception {
        mockMvc.perform(get("/api/admin/reports")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/users/1/ban")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointsRejectRegularUser() throws Exception {
        User user = createUser("regular");
        mockMvc.perform(get("/api/admin/reports").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/users").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/users/" + user.getId() + "/ban").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/users/" + user.getId() + "/unban").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/projects/1/unpublish").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/reports/1/dismiss").header("Authorization", bearer(user)))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportCreatesOpenReportVisibleToAdmin() throws Exception {
        User owner = createUser("reported");
        User reporter = createUser("reporter");
        User admin = createAdmin("admin1");
        Project project = createProject(owner, "Sketchy", "published");

        mockMvc.perform(post("/api/project/" + project.getId() + "/report")
                        .header("Authorization", bearer(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"spam"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/reports").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectTitle").value("Sketchy"))
                .andExpect(jsonPath("$[0].reporterUsername").value("reporter"))
                .andExpect(jsonPath("$[0].reason").value("spam"))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void reportRequiresReason() throws Exception {
        User owner = createUser("owner5");
        User reporter = createUser("reporter5");
        Project project = createProject(owner, "Fine", "published");

        mockMvc.perform(post("/api/project/" + project.getId() + "/report")
                        .header("Authorization", bearer(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"  "}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cannotReportOwnProject() throws Exception {
        User owner = createUser("selfreporter");
        Project project = createProject(owner, "Mine", "published");

        mockMvc.perform(post("/api/project/" + project.getId() + "/report")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"testing"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void duplicateOpenReportIsIgnored() throws Exception {
        User owner = createUser("owner6");
        User reporter = createUser("reporter6");
        Project project = createProject(owner, "Twice", "published");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/project/" + project.getId() + "/report")
                            .header("Authorization", bearer(reporter))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"reason":"spam"}"""))
                    .andExpect(status().isOk());
        }

        assertThat(projectReportRepository.count()).isEqualTo(1);
    }

    @Test
    void dismissMarksReportDismissedAndLogs() throws Exception {
        User owner = createUser("owner7");
        User reporter = createUser("reporter7");
        User admin = createAdmin("admin7");
        Project project = createProject(owner, "Dismissable", "published");

        mockMvc.perform(post("/api/project/" + project.getId() + "/report")
                        .header("Authorization", bearer(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"spam"}"""))
                .andExpect(status().isOk());

        long reportId = projectReportRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/admin/reports/" + reportId + "/dismiss")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        ProjectReport report = projectReportRepository.findById(reportId).orElseThrow();
        assertThat(report.getStatus()).isEqualTo("DISMISSED");

        ModerationLog log = moderationLogRepository.findAll().get(0);
        assertThat(log.getAction()).isEqualTo("DISMISS_REPORT");
        assertThat(log.getAdmin().getId()).isEqualTo(admin.getId());

        mockMvc.perform(get("/api/admin/reports").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void banSetsFlagAndLogsAndUnbanRestores() throws Exception {
        User target = createUser("bannable");
        User admin = createAdmin("admin8");

        mockMvc.perform(post("/api/admin/users/" + target.getId() + "/ban")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"abuse"}"""))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(target.getId()).orElseThrow().isBanned()).isTrue();
        assertThat(moderationLogRepository.findAll())
                .anyMatch(l -> "BAN".equals(l.getAction()) && l.getTargetId().equals(target.getId()));

        mockMvc.perform(post("/api/admin/users/" + target.getId() + "/unban")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(target.getId()).orElseThrow().isBanned()).isFalse();
        assertThat(moderationLogRepository.findAll())
                .anyMatch(l -> "UNBAN".equals(l.getAction()) && l.getTargetId().equals(target.getId()));
    }

    @Test
    void adminCannotBanSelf() throws Exception {
        User admin = createAdmin("admin9");

        mockMvc.perform(post("/api/admin/users/" + admin.getId() + "/ban")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(admin.getId()).orElseThrow().isBanned()).isFalse();
    }

    @Test
    void unpublishFlipsVisibilityAndActionsOpenReports() throws Exception {
        User owner = createUser("owner10");
        User reporter = createUser("reporter10");
        User admin = createAdmin("admin10");
        Project project = createProject(owner, "Takedown", "published");

        mockMvc.perform(post("/api/project/" + project.getId() + "/report")
                        .header("Authorization", bearer(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"stolen content"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/projects/" + project.getId() + "/unpublish")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"confirmed"}"""))
                .andExpect(status().isOk());

        assertThat(projectRepository.findById(project.getId()).orElseThrow().getVisibility()).isEqualTo("private");
        assertThat(projectReportRepository.findAll().get(0).getStatus()).isEqualTo("ACTIONED");
        assertThat(moderationLogRepository.findAll())
                .anyMatch(l -> "UNPUBLISH_PROJECT".equals(l.getAction()) && l.getTargetId().equals(project.getId()));
    }

    @Test
    void reportUnknownProjectIs404() throws Exception {
        User reporter = createUser("reporter11");
        mockMvc.perform(post("/api/project/999999/report")
                        .header("Authorization", bearer(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"ghost"}"""))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingReportedProjectRemovesItsReports() throws Exception {
        User owner = createUser("owner12");
        User reporter = createUser("reporter12");
        Project project = createProject(owner, "Reported then deleted", "published");

        mockMvc.perform(post("/api/project/" + project.getId() + "/report")
                        .header("Authorization", bearer(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"spam"}"""))
                .andExpect(status().isOk());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/project/" + project.getId())
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNoContent());

        assertThat(projectReportRepository.count()).isZero();
    }
}
