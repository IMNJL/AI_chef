package com.aichef.service;

import com.aichef.config.GoogleCalendarProperties;
import com.aichef.config.TelegramProperties;
import com.aichef.domain.enums.MeetingStatus;
import com.aichef.domain.model.GoogleOAuthState;
import com.aichef.domain.model.Meeting;
import com.aichef.domain.model.User;
import com.aichef.domain.model.UserGoogleConnection;
import com.aichef.repository.GoogleOAuthStateRepository;
import com.aichef.repository.MeetingRepository;
import com.aichef.repository.UserGoogleConnectionRepository;
import com.aichef.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleOAuthService {
    private static final long STATE_TTL_SEC = 15 * 60;

    private final GoogleCalendarProperties googleProperties;
    private final TelegramProperties telegramProperties;
    private final GoogleOAuthStateRepository stateRepository;
    private final UserRepository userRepository;
    private final UserGoogleConnectionRepository connectionRepository;
    private final MeetingRepository meetingRepository;
    private final GoogleCalendarService googleCalendarService;

    public Optional<String> createConnectUrl(Long telegramId) {
        if (telegramId == null || !googleProperties.isOAuthConfigured()) {
            return Optional.empty();
        }

        String redirectUri = buildRedirectUri();
        if (redirectUri == null) {
            return Optional.empty();
        }

        String state = buildSignedState(telegramId);
        GoogleOAuthState oauthState = new GoogleOAuthState();
        oauthState.setState(state);
        oauthState.setTelegramId(telegramId);
        oauthState.setExpiresAt(OffsetDateTime.now().plusSeconds(STATE_TTL_SEC));
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

    @Transactional
    public OAuthCallbackResult handleCallback(String state, String code) {
        if (state == null || state.isBlank() || code == null || code.isBlank()) {
            return new OAuthCallbackResult(null, false, "Missing state or code");
        }

        GoogleOAuthState oauthState = stateRepository.findById(state).orElse(null);
        Long telegramId;
        if (oauthState != null) {
            if (oauthState.getExpiresAt().isBefore(OffsetDateTime.now())) {
                stateRepository.deleteById(state);
                return new OAuthCallbackResult(oauthState.getTelegramId(), false, "Expired state. Retry connect.");
            }
            telegramId = oauthState.getTelegramId();
            stateRepository.deleteById(state);
        } else {
            telegramId = validateSignedStateAndGetTelegramId(state);
            if (telegramId == null) {
                return new OAuthCallbackResult(null, false, "Invalid or expired state. Retry connect from Telegram bot.");
            }
        }

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
        String assistantCalendarId = ensureAssistantCalendar(tokenResponse.accessToken(), connection.getCalendarId());
        connection.setCalendarId(assistantCalendarId == null || assistantCalendarId.isBlank()
                ? "primary"
                : assistantCalendarId);
        connection.setGoogleEmail(email);
        connection.setAccessToken(tokenResponse.accessToken());
        if (tokenResponse.refreshToken() != null && !tokenResponse.refreshToken().isBlank()) {
            connection.setRefreshToken(tokenResponse.refreshToken());
        }
        if (connection.getIcsToken() == null || connection.getIcsToken().isBlank()) {
            connection.setIcsToken(UUID.randomUUID().toString().replace("-", ""));
        }
        connection.setTokenExpiresAt(OffsetDateTime.now().plusSeconds(tokenResponse.expiresIn()));
        connectionRepository.save(connection);
        syncExistingMeetings(user);

        log.info("Google account connected for telegramId={}, email={}", telegramId, email);
        return new OAuthCallbackResult(
                telegramId,
                true,
                "Google Calendar connected: " + (email == null ? "unknown email" : email)
        );
    }

    public boolean isConnected(User user) {
        if (user == null) {
            return false;
        }
        return connectionRepository.findByUser(user)
                .filter(this::hasUsableConnection)
                .isPresent();
    }

    public boolean isConnected(Long telegramId) {
        if (telegramId == null) {
            return false;
        }
        User user = userRepository.findByTelegramId(telegramId).orElse(null);
        return isConnected(user);
    }

    public Optional<String> createIcsUrl(Long telegramId) {
        if (telegramId == null) {
            return Optional.empty();
        }
        User user = userRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return Optional.empty();
        }
        UserGoogleConnection connection = connectionRepository.findByUser(user).orElse(null);
        if (connection == null) {
            return Optional.empty();
        }
        String token = connection.getIcsToken();
        if (token == null || token.isBlank()) {
            token = UUID.randomUUID().toString().replace("-", "");
            connection.setIcsToken(token);
            connectionRepository.save(connection);
        }
        String baseUrl = telegramProperties.publicBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) {
                return Optional.empty();
            }
            StringBuilder origin = new StringBuilder(scheme).append("://").append(host);
            if (port > 0) {
                origin.append(":").append(port);
            }
            return Optional.of(origin + "/api/ical/" + token);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private boolean hasUsableConnection(UserGoogleConnection connection) {
        if (connection == null) {
            return false;
        }
        boolean hasRefreshToken = connection.getRefreshToken() != null && !connection.getRefreshToken().isBlank();
        boolean hasAccessToken = connection.getAccessToken() != null && !connection.getAccessToken().isBlank();
        return hasRefreshToken || hasAccessToken;
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

    private void syncExistingMeetings(User user) {
        if (user == null || !googleCalendarService.isEnabled()) {
            return;
        }
        ZoneId zoneId = resolveZone(user.getTimezone());
        List<Meeting> meetings = meetingRepository.findByCalendarDay_UserOrderByStartsAtAsc(user);
        for (Meeting meeting : meetings) {
            if (meeting.getStatus() == MeetingStatus.CANCELED) {
                continue;
            }
            if (meeting.getGoogleEventId() != null && !meeting.getGoogleEventId().isBlank()) {
                continue;
            }
            GoogleCalendarService.CreatedGoogleEvent created = googleCalendarService.createEvent(
                    user,
                    meeting.getTitle(),
                    meeting.getStartsAt(),
                    meeting.getEndsAt(),
                    meeting.getExternalLink(),
                    zoneId
            );
            if (created == null) {
                continue;
            }
            if (created.eventId() != null && !created.eventId().isBlank()) {
                meeting.setGoogleEventId(created.eventId());
            }
            if ((meeting.getExternalLink() == null || meeting.getExternalLink().isBlank())
                    && created.htmlLink() != null && !created.htmlLink().isBlank()) {
                meeting.setExternalLink(created.htmlLink());
            }
            meetingRepository.save(meeting);
        }
    }

    private ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("Europe/Moscow");
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception ignored) {
            return ZoneId.of("Europe/Moscow");
        }
    }

    private String ensureAssistantCalendar(String accessToken, String currentCalendarId) {
        if (accessToken == null || accessToken.isBlank()) {
            return currentCalendarId;
        }
        if (currentCalendarId != null && !currentCalendarId.isBlank() && !"primary".equalsIgnoreCase(currentCalendarId)) {
            return currentCalendarId;
        }
        try {
            RestClient googleClient = RestClient.builder().baseUrl(googleProperties.safeApiBase()).build();
            Map<String, Object> createPayload = Map.of(
                    "summary", "assistant",
                    "description", "AI Chef assistant calendar",
                    "timeZone", "Europe/Moscow"
            );
            Map<?, ?> created = googleClient.post()
                    .uri("/calendars")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(createPayload)
                    .retrieve()
                    .body(Map.class);

            String calendarId = created != null && created.get("id") instanceof String id ? id : null;
            if (calendarId == null || calendarId.isBlank()) {
                return "primary";
            }

            try {
                RestClient listClient = RestClient.builder().baseUrl("https://www.googleapis.com/calendar/v3").build();
                Map<String, Object> colorPayload = Map.of("colorId", "6");
                listClient.patch()
                        .uri("/users/me/calendarList/{calendarId}", calendarId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(colorPayload)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception ignored) {
                // color is best-effort only
            }

            return calendarId;
        } catch (Exception e) {
            log.warn("Failed to create assistant calendar: {}", e.getMessage());
            return "primary";
        }
    }

    private record TokenResponse(String accessToken, String refreshToken, long expiresIn) {
    }

    public record OAuthCallbackResult(Long telegramId, boolean connected, String message) {
    }

    private String buildSignedState(Long telegramId) {
        long exp = OffsetDateTime.now().plusSeconds(STATE_TTL_SEC).toEpochSecond();
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String payload = telegramId + ":" + exp + ":" + nonce;
        String signature = signPayload(payload);
        String payloadPart = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return payloadPart + "." + signature;
    }

    private Long validateSignedStateAndGetTelegramId(String state) {
        try {
            String[] parts = state.split("\\.");
            if (parts.length != 2) {
                return null;
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String expectedSig = signPayload(payload);
            if (!expectedSig.equals(parts[1])) {
                return null;
            }
            String[] payloadParts = payload.split(":");
            if (payloadParts.length != 3) {
                return null;
            }
            long telegramId = Long.parseLong(payloadParts[0]);
            long exp = Long.parseLong(payloadParts[1]);
            if (OffsetDateTime.now().toEpochSecond() > exp) {
                return null;
            }
            return telegramId;
        } catch (Exception e) {
            return null;
        }
    }

    private String signPayload(String payload) {
        try {
            String secret = (googleProperties.clientSecret() == null ? "" : googleProperties.clientSecret())
                    + "|"
                    + (telegramProperties.botToken() == null ? "" : telegramProperties.botToken());
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign OAuth state", e);
        }
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
