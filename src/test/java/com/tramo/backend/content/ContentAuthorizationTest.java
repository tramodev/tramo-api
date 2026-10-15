package com.tramo.backend.content;

import com.tramo.backend.content.repository.*;
import com.tramo.backend.content.service.ContentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ContentAuthorizationTest.Config.class)
class ContentAuthorizationTest {
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        ContentService contentService() {
            return new ContentService(mock(BlogPostRepository.class), mock(ChangelogEntryRepository.class), mock(RoadmapItemRepository.class));
        }
    }

    @Autowired ContentService content;

    @Test
    void anonymousCannotReadDrafts() {
        assertThatThrownBy(() -> content.listAdminContent()).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    @WithMockUser(authorities = "USER")
    void nonAdminCannotReadDraftsWriteOrDelete() {
        assertThatThrownBy(() -> content.listAdminContent()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> content.saveBlog(null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> content.saveChangelog(null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> content.saveRoadmap(null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> content.deleteBlog(1L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> content.deleteChangelog(1L)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> content.deleteRoadmap(1L)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(authorities = "ADMIN")
    void adminCanReadDrafts() {
        assertThat(content.listAdminContent()).isNotNull();
    }
}
