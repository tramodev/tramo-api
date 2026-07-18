package com.tramo.backend;

import com.jayway.jsonpath.JsonPath;
import com.tramo.backend.auth.service.EmailService;
import com.tramo.backend.auth.service.GoogleTokenVerifier;
import com.tramo.backend.common.ProjectIdCodec;
import com.tramo.backend.project.entity.Project;
import com.tramo.backend.project.repository.ProjectRepository;
import com.tramo.backend.security.jwt.JwtService;
import com.tramo.backend.user.Role;
import com.tramo.backend.user.entity.User;
import com.tramo.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    protected static final String TEST_JWT_SECRET = "dGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQtdGVzdC1zZWNyZXQ=";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("app.google.client-id", () -> "test-client-id");
        registry.add("app.project-id.salt", () -> "test-project-id-salt");
        registry.add("app.r2.account-id", () -> "test-account-id");
        registry.add("app.r2.access-key", () -> "test-access-key");
        registry.add("app.r2.secret-key", () -> "test-secret-key");
        registry.add("app.r2.bucket", () -> "test-bucket");
        registry.add("app.r2.public-base-url", () -> "https://test-bucket.example.com");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @MockitoBean
    protected EmailService emailService;

    @MockitoBean
    protected GoogleTokenVerifier googleTokenVerifier;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected JwtService jwtService;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected ProjectRepository projectRepository;

    @Autowired
    protected ProjectIdCodec projectIdCodec;

    @BeforeEach
    void cleanDatabase() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        if (!tables.isEmpty()) {
            jdbcTemplate.execute("TRUNCATE TABLE " + String.join(", ", tables) + " CASCADE");
        }
    }

    protected User createUser(String username) {
        return createUser(username, username + "@example.com", true, false, Role.USER);
    }

    protected User createAdmin(String username) {
        return createUser(username, username + "@example.com", true, false, Role.ADMIN);
    }

    protected User createUser(String username, String email, boolean verified, boolean banned, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("Passw0rd!"));
        user.setVisibility(true);
        user.setCreatedAt(new Date());
        user.setUpdatedAt(new Date());
        user.setRole(role);
        user.setEmailVerified(verified);
        user.setBanned(banned);
        return userRepository.save(user);
    }

    protected String bearer(User user) {
        return "Bearer " + jwtService.getToken(user);
    }

    protected String pid(Project project) {
        return projectIdCodec.encode(project.getId());
    }

    protected String pid(Long id) {
        return projectIdCodec.encode(id);
    }

    protected String postForProjectId(User asUser, String url, String body) throws Exception {
        String response = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                                .header("Authorization", bearer(asUser))
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    protected long postForId(User asUser, String url, String body) throws Exception {
        String response = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                                .header("Authorization", bearer(asUser))
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private static final AtomicInteger IP_COUNTER = new AtomicInteger();

    protected static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    protected static RequestPostProcessor uniqueIp() {
        int n = IP_COUNTER.incrementAndGet();
        return remoteAddr("10.9." + (n / 256) % 256 + "." + n % 256);
    }

    protected Project createProject(User owner, String title, String visibility) {
        return createProject(owner, title, visibility, null, null);
    }

    protected Project createProject(User owner, String title, String visibility, String description, String tags) {
        Project project = new Project();
        project.setTitle(title);
        project.setDescription(description);
        project.setVisibility(visibility);
        project.setTags(tags);
        project.setOwner(owner);
        project.setCreationDate(new Date());
        project.setModifiedDate(new Date());
        return projectRepository.save(project);
    }
}
