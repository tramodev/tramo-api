package com.tramo.backend.moderation;

import com.tramo.backend.AbstractIntegrationTest;
import com.tramo.backend.moderation.entity.ProjectReport;
import com.tramo.backend.moderation.entity.ReportStatus;
import com.tramo.backend.moderation.repository.ProjectReportRepository;
import com.tramo.backend.moderation.service.ModerationService;
import com.tramo.backend.notification.repository.NotificationRepository;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.user.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ModerationRegressionTest extends AbstractIntegrationTest {
    @Autowired ModerationService moderation;
    @Autowired ProjectReportRepository reports;
    @Autowired NotificationRepository notifications;

    ProjectReport report(Project project, User reporter) {
        ProjectReport report = new ProjectReport();
        report.setProject(project); report.setReporter(reporter); report.setReason("Reporter reason");
        report.setStatus(ReportStatus.OPEN); report.setCreatedDate(new Date());
        return reports.save(report);
    }

    @Test
    void administratorsCannotBeBannedAndWritesRequireAnAdminInTheService() throws Exception {
        var admin = createAdmin("protectedadmin"); var other = createAdmin("otheradmin"); var user = createUser("ordinaryuser");
        for (var target : List.of(admin, other)) {
            mockMvc.perform(post("/api/admin/users/" + target.getId() + "/ban").header("Authorization", bearer(admin)))
                    .andExpect(status().isForbidden());
            assertThat(userRepository.findById(target.getId()).orElseThrow().isBanned()).isFalse();
        }
        assertThatThrownBy(() -> moderation.banUser(other.getId(), user, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> moderation.listLog(user, 0, 20)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void reasonsRespectTheDatabaseLimitAtEveryHttpBoundary() throws Exception {
        var admin = createAdmin("reasonadmin"); var owner = createUser("reasonowner"); var reporter = createUser("reasonreporter");
        var project = createProject(owner, "Reason limit", "published");
        long comment = postForId(owner, "/api/project/" + pid(project) + "/comments", "{\"content\":\"Reported\"}");
        String tooLong = "{\"reason\":\"" + "x".repeat(256) + "\"}";
        for (String path : List.of("/api/project/" + pid(project) + "/report", "/api/comment/" + comment + "/report")) {
            mockMvc.perform(post(path).header("Authorization", bearer(reporter)).contentType("application/json").content(tooLong))
                    .andExpect(status().isBadRequest());
        }
        for (String path : List.of("/api/admin/projects/" + pid(project) + "/unpublish", "/api/admin/users/" + owner.getId() + "/ban", "/api/admin/comment-reports/999/uphold")) {
            mockMvc.perform(post(path).header("Authorization", bearer(admin)).contentType("application/json").content(tooLong))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/project/" + pid(project) + "/report").header("Authorization", bearer(reporter))
                        .contentType("application/json").content("{\"reason\":\"" + "x".repeat(255) + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/projects/" + pid(project) + "/unpublish").header("Authorization", bearer(admin))
                        .contentType("application/json").content("{\"reason\":\"" + "x".repeat(255) + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void closedReportsCannotChangeOrCreateAnotherLog() throws Exception {
        var admin = createAdmin("closedadmin"); var owner = createUser("closedowner"); var reporter = createUser("closedreporter");
        var project = createProject(owner, "Closed", "published"); var report = report(project, reporter);
        moderation.unpublishProject(project.getId(), admin, "Moderator reason");
        moderation.dismissReport(report.getId(), "PROJECT", admin);
        moderation.unpublishProject(project.getId(), admin, "Another reason");
        assertThat(reports.findById(report.getId()).orElseThrow().getStatus()).isEqualTo(ReportStatus.UPHELD);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM moderation_log", Long.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT reason FROM moderation_log", String.class)).isEqualTo("Moderator reason");
        assertThat(notifications.findAll()).singleElement().satisfies(n -> assertThat(n.getMessage()).contains("Moderator reason"));
        verify(emailService).sendModerationEmail(eq(owner.getEmail()), eq(owner.getUsername()), contains("Moderator reason"));
    }

    @Test
    void simultaneousUnpublishesOnlyCreateOneLogAndOneNotification() throws Exception {
        var admin = createAdmin("concurrentadmin"); var owner = createUser("concurrentowner");
        var project = createProject(owner, "Concurrent", "published");
        var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(5);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 5; index++) futures.add(executor.submit(() -> {
                try { start.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new RuntimeException(failure); }
                moderation.unpublishProject(project.getId(), admin, "Confirmed");
            }));
            start.countDown();
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM moderation_log", Long.class)).isEqualTo(1);
        assertThat(notifications.count()).isEqualTo(1);
        verify(emailService).sendModerationEmail(eq(owner.getEmail()), eq(owner.getUsername()), contains("Confirmed"));
    }

    @Test
    void blockedPublishingRequiresExplicitAdminReleaseAndNotifiesTheOwner() throws Exception {
        var admin = createAdmin("releaseadmin"); var owner = createUser("releaseowner");
        var project = createProject(owner, "Blocked", "published");
        project.setDescription("Description"); projectRepository.save(project);
        moderation.unpublishProject(project.getId(), admin, "Policy violation");
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner))).andExpect(status().isForbidden());
        for (String visibility : List.of("published", "unlisted")) {
            mockMvc.perform(put("/api/project/" + pid(project)).header("Authorization", bearer(owner)).contentType("application/json")
                            .content("{\"visibility\":\"" + visibility + "\"}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/api/admin/projects/" + pid(project) + "/allow-publishing").header("Authorization", bearer(owner))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/projects/" + pid(project) + "/allow-publishing").header("Authorization", bearer(admin))).andExpect(status().isOk());
        assertThat(projectRepository.findById(project.getId()).orElseThrow().isModerationBlocked()).isFalse();
        assertThat(notifications.findAll()).hasSize(2);
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner))).andExpect(status().isOk());
    }

    @Test
    void banAndUnbanAreIdempotentAndSendSeparateNotices() {
        var admin = createAdmin("banadmin"); var user = createUser("banrecipient");
        moderation.banUser(user.getId(), admin, "Abuse"); moderation.banUser(user.getId(), admin, "Abuse");
        moderation.unbanUser(user.getId(), admin); moderation.unbanUser(user.getId(), admin);
        assertThat(notifications.findAll()).hasSize(2).allSatisfy(n -> assertThat(n.getType()).isEqualTo("MODERATION"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM moderation_log", Long.class)).isEqualTo(2);
        verify(emailService).sendModerationEmail(eq(user.getEmail()), eq(user.getUsername()), eq("Your account has been banned. Reason: Abuse"));
        verify(emailService).sendModerationEmail(eq(user.getEmail()), eq(user.getUsername()), eq("Your account has been unbanned."));
    }

    @Test
    void searchTreatsSqlWildcardsAsLiteralCharacters() throws Exception {
        var admin = createAdmin("literaladmin"); createUser("literalmatch");
        for (String query : List.of("%", "_", "!")) {
            mockMvc.perform(get("/api/admin/users").param("q", query).header("Authorization", bearer(admin)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        }
    }

    @Test
    void reportAndAuditPagesAreBoundedFilteredAndPrivate() throws Exception {
        var admin = createAdmin("pagingadmin"); var owner = createUser("pagingowner"); var reporter = createUser("pagingreporter");
        var project = createProject(owner, "Pages", "published");
        for (int index = 0; index < 105; index++) report(project, reporter);
        mockMvc.perform(get("/api/admin/reports").param("page", "5").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(5)).andExpect(jsonPath("$.hasMore").value(false));
        mockMvc.perform(get("/api/admin/reports").param("type", "COMMENT").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mockMvc.perform(get("/api/admin/reports").param("type", "project").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(20));
        mockMvc.perform(get("/api/admin/reports").param("type", "comment").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        moderation.unpublishProject(project.getId(), admin, "Reviewed");
        mockMvc.perform(get("/api/admin/reports").param("status", "UPHELD").param("size", "1").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.hasMore").value(true));
        mockMvc.perform(get("/api/admin/moderation-log").header("Authorization", bearer(owner))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/moderation-log").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].adminUsername").value(admin.getUsername()))
                .andExpect(jsonPath("$.content[0].targetId").value(pid(project))).andExpect(jsonPath("$.content[0].publishingBlocked").value(true));
        for (String query : List.of("?page=-1", "?size=101", "?status=UNKNOWN", "?type=UNKNOWN")) {
            mockMvc.perform(get("/api/admin/reports" + query).header("Authorization", bearer(admin))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void invalidSiteContentIsForbiddenBeforeValidationForRegularUsers() throws Exception {
        var user = createUser("contentregular");
        for (String section : List.of("blog", "changelog", "roadmap")) {
            mockMvc.perform(post("/api/admin/content/" + section).header("Authorization", bearer(user)).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
    }
    @Test
    void commentActionsNotifyOnceAndDoNotReopenResolvedReports() throws Exception {
        var admin = createAdmin("deleteadmin"); var author = createUser("deleteauthor"); var reporter = createUser("deletereporter");
        var project = createProject(author, "Reported comment", "published");
        long commentId = postForId(author, "/api/project/" + pid(project) + "/comments", "{\"content\":\"Comment\"}");
        moderation.submitCommentReport(commentId, reporter, "Reporter reason");
        long reportId = jdbcTemplate.queryForObject("SELECT id FROM comment_report", Long.class);
        moderation.upholdCommentReport(reportId, admin, "Moderator reason");
        moderation.upholdCommentReport(reportId, admin, "Duplicate");
        moderation.dismissReport(reportId, "COMMENT", admin);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM comment_report WHERE id = ?", String.class, reportId)).isEqualTo("UPHELD");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM moderation_log", Long.class)).isEqualTo(1);
        assertThat(notifications.findAll()).singleElement().satisfies(n -> assertThat(n.getMessage()).contains("deleted your comment", "Moderator reason"));
        verify(emailService).sendModerationEmail(eq(author.getEmail()), eq(author.getUsername()), contains("Moderator reason"));
    }

    @Test
    void focusedCommentFindsItsPageAndRejectsCrossProjectIds() throws Exception {
        var owner = createUser("focusowner"); var project = createProject(owner, "Comments", "published");
        long last = 0;
        for (int index = 0; index < 25; index++) last = postForId(owner, "/api/project/" + pid(project) + "/comments", "{\"content\":\"Comment\"}");
        long reply = postForId(owner, "/api/project/" + pid(project) + "/comments", "{\"content\":\"Reply\",\"parentId\":" + last + "}");
        mockMvc.perform(get("/api/public/project/" + pid(project) + "/comments").param("focusId", Long.toString(reply)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.content[5].id").value(reply));
        var other = createProject(owner, "Other", "published");
        mockMvc.perform(get("/api/public/project/" + pid(other) + "/comments").param("focusId", Long.toString(reply))).andExpect(status().isNotFound());
    }

    @Test
    void dismissRequiresAnExplicitTypeWhenReportIdsCollide() throws Exception {
        var admin = createAdmin("typedadmin"); var owner = createUser("typedowner"); var reporter = createUser("typedreporter");
        var project = createProject(owner, "Colliding reports", "published");
        long commentId = postForId(owner, "/api/project/" + pid(project) + "/comments", "{\"content\":\"Comment\"}");
        jdbcTemplate.update("INSERT INTO project_report (id, project_id, reporter_id, reason, status, created_date) VALUES (103, ?, ?, 'Project reason', 'OPEN', now())", project.getId(), reporter.getId());
        jdbcTemplate.update("INSERT INTO comment_report (id, comment_id, reporter_id, reason, status, created_date) VALUES (103, ?, ?, 'Comment reason', 'OPEN', now())", commentId, reporter.getId());
        for (String query : List.of("", "?type=FOO", "?type=ALL", "?type=")) {
            mockMvc.perform(post("/api/admin/reports/103/dismiss" + query).header("Authorization", bearer(admin)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                    .andExpect(jsonPath("$.message").value("Invalid request parameter"))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }
        for (String table : List.of("project_report", "comment_report")) {
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM " + table + " WHERE id = 103", String.class)).isEqualTo("OPEN");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM moderation_log", Long.class)).isZero();
        mockMvc.perform(post("/api/admin/reports/103/dismiss").param("type", "comment").header("Authorization", bearer(admin))).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM project_report WHERE id = 103", String.class)).isEqualTo("OPEN");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM comment_report WHERE id = 103", String.class)).isEqualTo("DISMISSED");
        mockMvc.perform(post("/api/admin/reports/103/dismiss").param("type", "project").header("Authorization", bearer(admin))).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM project_report WHERE id = 103", String.class)).isEqualTo("DISMISSED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM moderation_log", Long.class)).isEqualTo(2);
    }

    @Test
    void invalidReportFiltersUseTheSameErrorContract() throws Exception {
        var admin = createAdmin("filteradmin");
        for (String filter : List.of("type", "status")) {
            for (String value : List.of("FOO", "", " ")) {
                mockMvc.perform(get("/api/admin/reports").param(filter, value).header("Authorization", bearer(admin)))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                        .andExpect(jsonPath("$.message").value("Invalid request parameter"))
                        .andExpect(jsonPath("$.timestamp").isNotEmpty());
            }
        }
    }

    @Test
    void reportStatusesIgnoreCaseAndOmittedFiltersKeepDefaults() throws Exception {
        var admin = createAdmin("statusadmin");
        var owner = createUser("statusowner");
        var reporter = createUser("statusreporter");
        var project = createProject(owner, "Status filters", "published");
        report(project, reporter);
        mockMvc.perform(get("/api/admin/reports").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));
        for (String status : List.of("open", "Open", "OPEN", "Upheld", "upheld", "dismissed", "Dismissed")) {
            mockMvc.perform(get("/api/admin/reports").param("status", status).header("Authorization", bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(status.equalsIgnoreCase("open") ? 1 : 0));
        }
    }

    @Test
    void publishingErrorsPreserveThePublicModerationMessage() throws Exception {
        var admin = createAdmin("blockedmessageadmin"); var owner = createUser("blockedmessageowner");
        var project = createProject(owner, "Blocked message", "published");
        moderation.unpublishProject(project.getId(), admin, "Review");
        mockMvc.perform(post("/api/project/" + pid(project) + "/publish").header("Authorization", bearer(owner)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Publishing is blocked by moderation"));
        mockMvc.perform(put("/api/project/" + pid(project)).header("Authorization", bearer(owner)).contentType("application/json").content("{\"visibility\":\"published\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Publishing is blocked by moderation"));
    }

}
