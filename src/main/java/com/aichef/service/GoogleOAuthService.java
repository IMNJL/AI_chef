package com.aichef.service;

import com.aichef.config.GoogleCalendarProperties;
import com.aichef.config.TelegramProperties;
import com.aichef.domain.model.GoogleOAuthState;
import com.aichef.domain.model.User;
import com.aichef.domain.model.UserGoogleConnection;
import com.aichef.repository.GoogleOAuthStateRepository;
import com.aichef.repository.UserGoogleConnectionRepository;
import com.aichef.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleOAuthService {

    private final GoogleCalendarProperties googleProperties;
    private final TelegramProperties telegramProperties;
    private final GoogleOAuthStateRepository stateRepository;
    private final UserRepository userRepository;
    private final UserGoogleConnectionRepository connectionRepository;

    public Optional<String> createConnectUrl(Long telegramId) {
        if (telegramId == null || !googleProperties.isOAuthConfigured()) {
            return Optional.empty();
        }

        String redirectUri = buildRedirectUri();
        if (redirectUri == null) {
            return Optional.empty();
        }

        String state = UUID.randomUUID().toString().replace("-", "");
        GoogleOAuthState oauthState = new GoogleOAuthState();
        oauthState.setState(state);
        oauthState.setTelegramId(telegramId);
        oauthState.setExpiresAt(OffsetDateTime.now().plusMinutes(15));
        stateRepository.save(oauthState);

        String authUrl = UriComponentsBuilder
                .fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
                .queryParam("response_type", "code")
                .queryParam("client_id", googleProperties.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", googleProperties.safeScopes())
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();

        return Optional.of(authUrl);
    }

    public String handleCallback(String state, String code) {
        if (state == null || state.isBlank() || code == null || code.isBlank()) {
            return "Missing state or code";
        }

        GoogleOAuthState oauthState = stateRepository.findById(state).orElse(null);
        if (oauthState == null) {
            return "Invalid state";
        }
        if (oauthState.getExpiresAt().isBefore(OffsetDateTime.now())) {
            stateRepository.deleteById(state);
            return "Expired state. Retry connect.";
        }

        Long telegramId = oauthState.getTelegramId();
        stateRepository.deleteById(state);

        User user = userRepository.findByTelegramId(telegramId)
                .orElseGet(() -> {
                    User u = new User();
                    u.setTelegramId(telegramId);
                    return userRepository.save(u);
                });

        TokenResponse tokenResponse = exchangeCodeForToken(code);
        String email = fetchGoogleEmail(tokenResponse.accessToken());

        UserGoogleConnection connection = connectionRepository.findByUser(user)
                .orElseGet(UserGoogleConnection::new);
        connection.setUser(user);
        connection.setCalendarId(googleProperties.calendarId() == null || googleProperties.calendarId().isBlank()
                ? "primary"
                : googleProperties.calendarId());
        connection.setGoogleEmail(email);
        connection.setAccessToken(tokenResponse.accessToken());
        if (tokenResponse.refreshToken() != null && !tokenResponse.refreshToken().isBlank()) {
            connection.setRefreshToken(tokenResponse.refreshToken());
        }
        connection.setTokenExpiresAt(OffsetDateTime.now().plusSeconds(tokenResponse.expiresIn()));
        connectionRepository.save(connection);

        log.info("Google account connected for telegramId={}, email={}", telegramId, email);
        return "Google Calendar connected: " + (email == null ? "unknown email" : email);
    }

    private TokenResponse exchangeCodeForToken(String code) {
        RestClient tokenClient = RestClient.builder().baseUrl(googleProperties.safeTokenUri()).build();
        String redirectUri = buildRedirectUri();
        if (redirectUri == null) {
            throw new IllegalStateException("APP_PUBLIC_BASE_URL is invalid for OAuth redirect.");
        }

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("code", code);
        body.add("client_id", googleProperties.clientId());
        body.add("client_secret", googleProperties.clientSecret());
        body.add("redirect_uri", redirectUri);
        body.add("grant_type", "authorization_code");

        Map<?, ?> tokenResp = tokenClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (tokenResp == null || !(tokenResp.get("access_token") instanceof String accessToken)) {
            throw new IllegalStateException("Google token exchange failed: " + tokenResp);
        }

        String refreshToken = tokenResp.get("refresh_token") instanceof String r ? r : null;
        Number expiresIn = tokenResp.get("expires_in") instanceof Number n ? n : 3600;
        return new TokenResponse(accessToken, refreshToken, expiresIn.longValue());
    }

    private String fetchGoogleEmail(String accessToken) {
        RestClient googleClient = RestClient.builder().baseUrl("https://www.googleapis.com").build();
        Map<?, ?> resp = googleClient.get()
                .uri(URI.create("https://www.googleapis.com/oauth2/v3/userinfo"))
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(Map.class);
        if (resp == null) {
            return null;
        }
        Object email = resp.get("email");
        return email instanceof String s ? s : null;
    }

    private record TokenResponse(String accessToken, String refreshToken, long expiresIn) {
    }

    private String buildRedirectUri() {
        String baseUrl = telegramProperties.publicBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) {
                return null;
            }
            StringBuilder origin = new StringBuilder(scheme).append("://").append(host);
            if (port > 0) {
                origin.append(":").append(port);
            }
            return origin + googleProperties.safeRedirectPath();
        } catch (Exception e) {
            log.warn("Invalid APP_PUBLIC_BASE_URL for OAuth redirect: {}", baseUrl);
            return null;
        }
    }
}
