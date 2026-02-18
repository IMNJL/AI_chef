package com.aichef.service;

import com.aichef.config.AiProperties;
import com.aichef.domain.enums.FilterClassification;
import com.aichef.domain.enums.InboundStatus;
import com.aichef.domain.enums.PriorityLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiIntentService {

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public Optional<MessageIntent> detectIntent(String text, ZoneId zoneId) {
        if (!aiProperties.hasGeminiKey() || text == null || text.isBlank()) {
            return Optional.empty();
        }

        try {
            RestClient geminiClient = RestClient.builder()
                    .baseUrl(resolveBaseUrl())
                    .build();

            String prompt = buildIntentPrompt(text, zoneId);
            Map<String, Object> payload = buildTextPayload(prompt);

            Map<?, ?> response = geminiClient.post()
                    .uri(uri -> uri
                            .path("/v1beta/models/{model}:generateContent")
                            .queryParam("key", aiProperties.geminiApiKey())
                            .build(resolveModel()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);

            String raw = extractTextResponse(response);
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(stripJsonFence(raw));
            return Optional.of(toIntent(root, zoneId));
        } catch (Exception e) {
            log.warn("Gemini intent detection failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private MessageIntent toIntent(JsonNode json, ZoneId zoneId) {
        BotAction action = parseAction(textOrNull(json, "action"));

        String title = valueOrDefault(textOrNull(json, "title"), "Задача");
        String response = valueOrDefault(textOrNull(json, "response"), "Принял.");
        String externalLink = textOrNull(json, "externalLink");
        ScheduleRange scheduleRange = parseScheduleRange(textOrNull(json, "scheduleRange"));

        OffsetDateTime startsAt = parseDateTime(textOrNull(json, "date"), textOrNull(json, "time"), zoneId);
        Integer durationMinutes = intOrDefault(json, "durationMinutes", 60);
        OffsetDateTime endsAt = startsAt == null ? null : startsAt.plusMinutes(durationMinutes);
        OffsetDateTime dueAt = parseDateTime(textOrNull(json, "dueDate"), textOrNull(json, "dueTime"), zoneId);

        return switch (action) {
            case CREATE_MEETING -> new MessageIntent(action, FilterClassification.MEETING, InboundStatus.PROCESSED, title,
                    PriorityLevel.HIGH, startsAt, endsAt, null, null, null, null, externalLink, response);
            case SHOW_SCHEDULE -> new MessageIntent(action, FilterClassification.INFO_ONLY, InboundStatus.PROCESSED, title,
                    PriorityLevel.LOW, null, null, null, scheduleRange == null ? ScheduleRange.TODAY : scheduleRange,
                    null, null, externalLink, response);
            case IGNORE -> new MessageIntent(action, FilterClassification.IGNORE, InboundStatus.IGNORED, title,
                    PriorityLevel.LOW, null, null, null, null, null, null, externalLink, response);
            case ASK_CLARIFICATION -> new MessageIntent(action, FilterClassification.ASK_CLARIFICATION, InboundStatus.NEEDS_CLARIFICATION,
                    title, PriorityLevel.MEDIUM, null, null, null, null, null, null, externalLink, response);
            case INFO -> new MessageIntent(action, FilterClassification.INFO_ONLY, InboundStatus.PROCESSED, title,
                    PriorityLevel.LOW, null, null, null, null, null, null, externalLink, response);
            case CREATE_NOTE -> new MessageIntent(action, FilterClassification.INFO_ONLY, InboundStatus.PROCESSED, title,
                    PriorityLevel.LOW, null, null, null, null, null,
                    valueOrDefault(textOrNull(json, "noteContent"), title), externalLink, response);
            case EDIT_NOTE -> new MessageIntent(action, FilterClassification.INFO_ONLY, InboundStatus.PROCESSED, title,
                    PriorityLevel.LOW, null, null, null, null,
                    textOrNull(json, "noteId"), textOrNull(json, "noteContent"), externalLink, response);
            case SHOW_NOTES -> new MessageIntent(action, FilterClassification.INFO_ONLY, InboundStatus.PROCESSED, title,
                    PriorityLevel.LOW, null, null, null, null, null, null, externalLink, response);
            case CREATE_TASK -> new MessageIntent(action, FilterClassification.TASK, InboundStatus.PROCESSED, title,
                    PriorityLevel.MEDIUM, null, null, dueAt, null, null, null, externalLink, response);
        };
    }

    private String buildIntentPrompt(String text, ZoneId zoneId) {
        LocalDate now = LocalDate.now(zoneId);
        return "You are an assistant for a Telegram productivity bot. "
                + "Current date is " + now + " in timezone " + zoneId + ". "
                + "Return ONLY JSON object with fields: "
                + "action(one of CREATE_TASK,CREATE_MEETING,SHOW_SCHEDULE,CREATE_NOTE,EDIT_NOTE,SHOW_NOTES,INFO,IGNORE,ASK_CLARIFICATION),"
                + "title,date,time,durationMinutes,dueDate,dueTime,scheduleRange(one of TODAY,TOMORROW,WEEK),"
                + "noteId,noteContent,externalLink,response. "
                + "Message: " + text;
    }

    private Map<String, Object> buildTextPayload(String prompt) {
        Map<String, Object> textPart = Map.of("text", prompt);
        Map<String, Object> partsContainer = Map.of("parts", List.of(textPart));
        Map<String, Object> genConfig = new HashMap<>();
        genConfig.put("temperature", 0.1);
        genConfig.put("responseMimeType", "application/json");
        return Map.of("contents", List.of(partsContainer), "generationConfig", genConfig);
    }

    @SuppressWarnings("unchecked")
    private String extractTextResponse(Map<?, ?> response) {
        if (response == null) {
            return null;
        }
        Object candidatesObj = response.get("candidates");
        if (!(candidatesObj instanceof List<?> candidates) || candidates.isEmpty()) {
            return null;
        }
        Object first = candidates.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) {
            return null;
        }
        Object contentObj = firstMap.get("content");
        if (!(contentObj instanceof Map<?, ?> contentMap)) {
            return null;
        }
        Object partsObj = contentMap.get("parts");
        if (!(partsObj instanceof List<?> parts) || parts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Object part : parts) {
            if (part instanceof Map<?, ?> partMap && partMap.get("text") instanceof String s) {
                sb.append(s);
            }
        }
        return sb.toString();
    }

    private String stripJsonFence(String input) {
        String out = input.trim();
        if (out.startsWith("```")) {
            out = out.replaceFirst("^```json", "").replaceFirst("^```", "");
            out = out.replaceFirst("```$", "").trim();
        }
        return out;
    }

    private BotAction parseAction(String raw) {
        if (raw == null) {
            return BotAction.CREATE_TASK;
        }
        try {
            return BotAction.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignored) {
            return BotAction.CREATE_TASK;
        }
    }

    private ScheduleRange parseScheduleRange(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return ScheduleRange.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignored) {
            return null;
        }
    }

    private OffsetDateTime parseDateTime(String dateRaw, String timeRaw, ZoneId zoneId) {
        if (dateRaw == null || dateRaw.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(dateRaw);
            LocalTime time = (timeRaw == null || timeRaw.isBlank()) ? LocalTime.of(11, 0) : LocalTime.parse(timeRaw);
            return OffsetDateTime.now(zoneId)
                    .withYear(date.getYear())
                    .withMonth(date.getMonthValue())
                    .withDayOfMonth(date.getDayOfMonth())
                    .withHour(time.getHour())
                    .withMinute(time.getMinute())
                    .withSecond(0)
                    .withNano(0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer intOrDefault(JsonNode node, String field, int defaultValue) {
        JsonNode value = node.get(field);
        return value != null && value.isInt() ? value.asInt() : defaultValue;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String resolveBaseUrl() {
        if (aiProperties.geminiBaseUrl() == null || aiProperties.geminiBaseUrl().isBlank()) {
            return "https://generativelanguage.googleapis.com";
        }
        return aiProperties.geminiBaseUrl();
    }

    private String resolveModel() {
        if (aiProperties.geminiModel() == null || aiProperties.geminiModel().isBlank()) {
            return "gemini-2.0-flash";
        }
        return aiProperties.geminiModel();
    }
}
