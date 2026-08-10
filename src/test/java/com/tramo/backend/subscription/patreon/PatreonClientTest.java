package com.tramo.backend.subscription.patreon;

import com.tramo.backend.exception.InvalidTokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PatreonClientTest {

    private PatreonClient patreonClient;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        patreonClient = new PatreonClient();
        ReflectionTestUtils.setField(patreonClient, "clientId", "cid");
        ReflectionTestUtils.setField(patreonClient, "clientSecret", "csecret");
        ReflectionTestUtils.setField(patreonClient, "redirectUri", "https://tramo.dev/callback");

        RestClient.Builder builder = RestClient.builder().baseUrl("https://www.patreon.com");
        server = MockRestServiceServer.bindTo(builder).build();
        ReflectionTestUtils.setField(patreonClient, "restClient", builder.build());
    }

    @Test
    void exchangeCodeReturnsTokens() {
        server.expect(requestTo("https://www.patreon.com/api/oauth2/token"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("code=abc123")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=authorization_code")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("client_id=cid")))
                .andRespond(withSuccess("""
                        {"access_token":"at-1","refresh_token":"rt-1","expires_in":2678400}""",
                        MediaType.APPLICATION_JSON));

        PatreonClient.PatreonTokens tokens = patreonClient.exchangeCode("abc123");

        assertThat(tokens.accessToken()).isEqualTo("at-1");
        assertThat(tokens.refreshToken()).isEqualTo("rt-1");
        server.verify();
    }

    @Test
    void exchangeCodeRejectsResponseWithoutAccessToken() {
        server.expect(requestTo("https://www.patreon.com/api/oauth2/token"))
                .andRespond(withSuccess("{\"refresh_token\":\"rt-1\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> patreonClient.exchangeCode("abc123"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("empty token response");
    }

    @Test
    void exchangeCodeWrapsHttpErrorWithStatusAndBody() {
        server.expect(requestTo("https://www.patreon.com/api/oauth2/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> patreonClient.exchangeCode("stale-code"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("invalid_grant");
    }

    @Test
    void exchangeCodeWrapsServerError() {
        server.expect(requestTo("https://www.patreon.com/api/oauth2/token"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> patreonClient.exchangeCode("abc123"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("Failed to exchange Patreon authorization code");
    }

    @Test
    void fetchIdentitySendsBearerTokenAndDetectsActivePatron() {
        server.expect(requestTo("https://www.patreon.com/api/oauth2/v2/identity?include=memberships"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer at-1"))
                .andRespond(withSuccess("""
                        {"data":{"id":"patreon-42","type":"user"},
                         "included":[{"type":"member","attributes":{"patron_status":"active_patron"}}]}""",
                        MediaType.APPLICATION_JSON));

        PatreonClient.PatreonIdentity identity = patreonClient.fetchIdentity("at-1");

        assertThat(identity.patreonUserId()).isEqualTo("patreon-42");
        assertThat(identity.activePatron()).isTrue();
        server.verify();
    }

    @Test
    void fetchIdentityTreatsFormerPatronAsInactive() {
        server.expect(requestTo("https://www.patreon.com/api/oauth2/v2/identity?include=memberships"))
                .andRespond(withSuccess("""
                        {"data":{"id":"patreon-42"},
                         "included":[{"attributes":{"patron_status":"former_patron"}}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(patreonClient.fetchIdentity("at-1").activePatron()).isFalse();
    }

    @Test
    void fetchIdentityTreatsMissingMembershipsAsInactive() {
        server.expect(requestTo("https://www.patreon.com/api/oauth2/v2/identity?include=memberships"))
                .andRespond(withSuccess("{\"data\":{\"id\":\"patreon-42\"}}", MediaType.APPLICATION_JSON));

        PatreonClient.PatreonIdentity identity = patreonClient.fetchIdentity("at-1");

        assertThat(identity.patreonUserId()).isEqualTo("patreon-42");
        assertThat(identity.activePatron()).isFalse();
    }

    @Test
    void fetchIdentityIgnoresMembershipWithoutAttributes() {
        server.expect(requestTo("https://www.patreon.com/api/oauth2/v2/identity?include=memberships"))
                .andRespond(withSuccess("""
                        {"data":{"id":"patreon-42"},"included":[{"type":"member"}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(patreonClient.fetchIdentity("at-1").activePatron()).isFalse();
    }

    @Test
    void fetchIdentityFindsActivePatronAmongSeveralMemberships() {
        server.expect(requestTo("https://www.patreon.com/api/oauth2/v2/identity?include=memberships"))
                .andRespond(withSuccess("""
                        {"data":{"id":"patreon-42"},
                         "included":[{"attributes":{"patron_status":"declined_patron"}},
                                     {"attributes":{"patron_status":"active_patron"}}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(patreonClient.fetchIdentity("at-1").activePatron()).isTrue();
    }

    @Test
    void fetchIdentityRejectsResponseWithoutData() {
        server.expect(requestTo("https://www.patreon.com/api/oauth2/v2/identity?include=memberships"))
                .andRespond(withSuccess("{\"included\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> patreonClient.fetchIdentity("at-1"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("empty identity response");
    }

    @Test
    void fetchIdentityWrapsUnauthorized() {
        server.expect(requestTo("https://www.patreon.com/api/oauth2/v2/identity?include=memberships"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errors\":[{\"code\":1}]}"));

        assertThatThrownBy(() -> patreonClient.fetchIdentity("expired"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("Failed to fetch Patreon identity")
                .hasMessageContaining("401");
    }
}
