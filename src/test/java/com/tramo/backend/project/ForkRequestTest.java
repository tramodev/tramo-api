package com.tramo.backend.project;

import com.tramo.backend.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ForkRequestTest extends AbstractIntegrationTest {
    @Test
    void retriesReuseRecentForkButLaterForksRemainPossible() throws Exception {
        var author = createUser("retryauthor");
        var forker = createUser("retryforker");
        var source = createProject(author, "Original", "published", "Description", null);
        String path = "/api/project/" + pid(source) + "/fork";
        String first = postForProjectId(forker, path, "");
        assertThat(postForProjectId(forker, path, "")).isEqualTo(first);
        assertThat(projectRepository.countByForkedFromOwnerIdAndOwnerIdNot(author.getId(), author.getId())).isEqualTo(1);

        jdbcTemplate.update("update project set creation_date = creation_date - interval '11 seconds' where id = ?",
                projectIdCodec.decode(first));
        assertThat(postForProjectId(forker, path, "")).isNotEqualTo(first);
        jdbcTemplate.update("update project set visibility = 'private' where id = ?", source.getId());
        mockMvc.perform(post(path).header("Authorization", bearer(forker))).andExpect(status().isNotFound());
    }

    @Test
    void simultaneousRequestsCreateOneForkAndOneForkNotification() throws Exception {
        var author = createUser("parallelauthor");
        var forker = createUser("parallelforker");
        var source = createProject(author, "Original", "published", "Description", null);
        var barrier = new CyclicBarrier(4);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var calls = new ArrayList<Future<String>>();
            for (int i = 0; i < 4; i++) {
                calls.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return postForProjectId(forker, "/api/project/" + pid(source) + "/fork", "");
                }));
            }
            var ids = new ArrayList<String>();
            for (var call : calls) ids.add(call.get(30, TimeUnit.SECONDS));
            assertThat(ids.stream().distinct()).hasSize(1);
            assertThat(projectRepository.countByForkedFromOwnerIdAndOwnerIdNot(author.getId(), author.getId())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("select count(*) from notification where recipient_id = ? and type = 'FORK'",
                    Long.class, author.getId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
