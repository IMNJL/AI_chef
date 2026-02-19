package com.aichef.service;

import com.aichef.config.GoogleCalendarProperties;
import com.aichef.domain.model.User;
import com.aichef.domain.model.UserGoogleConnection;
import com.aichef.repository.UserGoogleConnectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleCalendarService {

    private final GoogleCalendarProperties properties;
    private final ObjectMapper objectMapper;
    private final UserGoogleConnectionRepository userGoogleConnectionRepository;

    private volatile String cachedAccessToken;
    private volatile long tokenExpiresAtEpochSec;

    public boolean isEnabled() {
        return properties.isOAuthConfigured();
    }

    public List<CalendarEventView> listEvents(User user, LocalDate from, LocalDate to, ZoneId zoneId) {
        String calendarId = resolveCalendarId(user);
        String accessToken = resolveAccessToken(user);
        if (calendarId == null || accessToken == null) {
            return List.of();
        }

        try {
            RestClient client = RestClient.builder().baseUrl(properties.safeApiBase()).build();
            OffsetDateTime timeMin = from.atStartOfDay(zoneId).toOffsetDateTime();
            OffsetDateTime timeMax = to.plusDays(1).atStartOfDay(zoneId).toOffsetDateTime();

            Map<?, ?> response = client.get()
                    .uri(uri -> uri
                            .path("/calendars/{calendarId}/events")
                            .queryParam("singleEvents", true)
                            .queryParam("orderBy", "startTime")
                            .queryParam("maxResults", 2500)
                            .queryParam("timeMin", timeMin.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                            .queryParam("timeMax", timeMax.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                            .build(calendarId))
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(Map.class);

            return parseEvents(response);
        } catch (Exception e) {
            log.error("Failed to list Google Calendar events: {}", e.getMessage());
            return List.of();
        }
    }

    public CreatedGoogleEvent createEvent(User user, String title, OffsetDateTime startsAt, OffsetDateTime endsAt, String externalLink, ZoneId zoneId) {
        String calendarId = resolveCalendarId(user);
        String accessToken = resolveAccessToken(user);
        if (calendarId == null || accessToken == null) {
            return null;
        }

        try {
            RestClient client = RestClient.builder().baseUrl(properties.safeApiBase()).build();

            Map<String, Object> payload = new HashMap<>();
            payload.put("summary", title);
            payload.put("description", externalLink);
            payload.put("start", Map.of(
                    "dateTime", startsAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "timeZone", zoneId.getId()
            ));
            payload.put("end", Map.of(
                    "dateTime", endsAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "timeZone", zoneId.getId()
            ));

            Map<?, ?> response = client.post()
                    .uri("/calendars/{calendarId}/events", calendarId)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return null;
            }
            Object eventId = response.get("id");
            Object htmlLink = response.get("htmlLink");
            String id = eventId instanceof String s ? s : null;
            String link = htmlLink instanceof String s ? s : null;
            return new CreatedGoogleEvent(id, link);
        } catch (Exception e) {
            log.error("Failed to create Google Calendar event: {}", e.getMessage());
            return null;
        }
    }

    public record CreatedGoogleEvent(String eventId, String htmlLink) {
    }

    private synchronized String getAccessToken(UserGoogleConnection connection) {
        long now = System.currentTimeMillis() / 1000;
        if (connection != null
                && connection.getAccessToken() != null
                && connection.getTokenExpiresAt() != null
                && connection.getTokenExpiresAt().toEpochSecond() - now > 30) {
            return connection.getAccessToken();
        }

        if (connection == null && cachedAccessToken != null && now < tokenExpiresAtEpochSec - 30) {
            return cachedAccessToken;
        }

        RestClient tokenClient = RestClient.builder().baseUrl(properties.safeTokenUri()).build();
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", properties.clientId());
        body.add("client_secret", properties.clientSecret());
        String refreshToken = connection != null ? connection.getRefreshToken() : properties.refreshToken();
        body.add("refresh_token", refreshToken);
        body.add("grant_type", "refresh_token");

        try {
            Map<?, ?> tokenResp = tokenClient.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (tokenResp == null || !(tokenResp.get("access_token") instanceof String token)) {
                throw new IllegalStateException("Google token response invalid: " + tokenResp);
            }
            Number expiresIn = tokenResp.get("expires_in") instanceof Number n ? n : 3600;
            if (connection != null) {
                connection.setAccessToken(token);
                connection.setTokenExpiresAt(OffsetDateTime.now().plusSeconds(expiresIn.longValue()));
                if (tokenResp.get("refresh_token") instanceof String newRefresh && !newRefresh.isBlank()) {
                    connection.setRefreshToken(newRefresh);
                }
                userGoogleConnectionRepository.save(connection);
                return connection.getAccessToken();
            } else {
                cachedAccessToken = token;
                tokenExpiresAtEpochSec = now + expiresIn.longValue();
                return cachedAccessToken;
            }
        } catch (RestClientException e) {
            throw new IllegalStateException("Google OAuth token refresh failed: " + e.getMessage(), e);
        }
    }

    private String resolveCalendarId(User user) {
        return userGoogleConnectionRepository.findByUser(user)
                .map(UserGoogleConnection::getCalendarId)
                .filter(id -> id != null && !id.isBlank())
                .orElseGet(() -> {
                    if (properties.isGlobalCalendarConfigured()) {
                        return properties.calendarId();
                    }
                    return null;
                });
    }

    private String resolveAccessToken(User user) {
        try {
            UserGoogleConnection connection = userGoogleConnectionRepository.findByUser(user).orElse(null);
            if (connection != null && connection.getRefreshToken() != null && !connection.getRefreshToken().isBlank()) {
                return getAccessToken(connection);
            }
            if (properties.isGlobalCalendarConfigured()) {
                return getAccessToken(null);
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to resolve Google access token for user {}: {}", user.getId(), e.getMessage());
            return null;
        }
    }

    private List<CalendarEventView> parseEvents(Map<?, ?> response) {
        if (response == null) {
            return List.of();
        }

        JsonNode root = objectMapper.valueToTree(response);
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            return List.of();
        }

        List<CalendarEventView> list = new ArrayList<>();
        for (JsonNode item : items) {
            String summary = textOr(item, "summary", "Без названия");
            String htmlLink = textOr(item, "htmlLink", null);

            OffsetDateTime start = parseGoogleDateNode(item.path("start"));
            OffsetDateTime end = parseGoogleDateNode(item.path("end"));
            if (start == null || end == null) {
                continue;
            }

            list.add(new CalendarEventView(summary, start, end, "google", htmlLink));
        }

        return list;
    }

    private OffsetDateTime parseGoogleDateNode(JsonNode node) {
        JsonNode dateTimeNode = node.get("dateTime");
        if (dateTimeNode != null && !dateTimeNode.isNull()) {
            try {
                return OffsetDateTime.parse(dateTimeNode.asText());
            } catch (Exception ignored) {
            }
        }

        JsonNode dateNode = node.get("date");
        if (dateNode != null && !dateNode.isNull()) {
            try {
                return LocalDate.parse(dateNode.asText()).atStartOfDay().atOffset(OffsetDateTime.now().getOffset());
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private String textOr(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }
}
