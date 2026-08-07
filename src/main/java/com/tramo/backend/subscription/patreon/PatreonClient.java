package com.tramo.backend.subscription.patreon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tramo.backend.exception.InvalidTokenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;

@Component
public class PatreonClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://www.patreon.com")
            .requestFactory(timeoutFactory())
            .build();

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    @Value("${app.patreon.client-id}")
    private String clientId;
    @Value("${app.patreon.client-secret}")
    private String clientSecret;
    @Value("${app.patreon.redirect-uri}")
    private String redirectUri;

    public record PatreonTokens(String accessToken, String refreshToken) {
    }

    
    public record PatreonIdentity(String patreonUserId, boolean activePatron) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IdentityResponse(IdentityData data, List<MembershipIncluded> included) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IdentityData(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MembershipIncluded(MembershipAttributes attributes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record MembershipAttributes(@JsonProperty("patron_status") String patronStatus) {
    }

    public PatreonTokens exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("grant_type", "authorization_code");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);

        TokenResponse response;
        try {
            response = restClient.post()
                    .uri("/api/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientException ex) {
            throw new InvalidTokenException("Failed to exchange Patreon authorization code: " + describe(ex), ex);
        }
        if (response == null || response.accessToken() == null) {
            throw new InvalidTokenException("Failed to exchange Patreon authorization code: empty token response");
        }
        return new PatreonTokens(response.accessToken(), response.refreshToken());
    }

    public PatreonIdentity fetchIdentity(String accessToken) {
        IdentityResponse response;
        try {
            
            
            
            response = restClient.get()
                    .uri("/api/oauth2/v2/identity?include=memberships")
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .retrieve()
                    .body(IdentityResponse.class);
        } catch (RestClientException ex) {
            throw new InvalidTokenException("Failed to fetch Patreon identity: " + describe(ex), ex);
        }
        if (response == null || response.data() == null) {
            throw new InvalidTokenException("Failed to fetch Patreon identity: empty identity response");
        }

        boolean activePatron = response.included() != null && response.included().stream()
                .anyMatch(membership -> membership.attributes() != null
                        && "active_patron".equals(membership.attributes().patronStatus()));
        return new PatreonIdentity(response.data().id(), activePatron);
    }

    
    
    
    private static String describe(RestClientException ex) {
        if (ex instanceof RestClientResponseException responseEx) {
            return responseEx.getStatusCode() + " " + responseEx.getResponseBodyAsString();
        }
        return ex.getMessage();
    }
}
