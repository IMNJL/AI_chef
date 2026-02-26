package com.aichef.service;

import com.aichef.config.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaStructuredParsingService {

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public boolean isEnabled() {
        return aiProperties.hasCloudLlm() || aiProperties.hasOllama();
    }

    public ParsedEventData extractEventData(String text, ZoneId zoneId) {
        if (text == null || text.isBlank()) {
            return ParsedEventData.empty();
        }
        if (!isEnabled()) {
            return ParsedEventData.empty();
        }

        try {
            String today = LocalDate.now(zoneId == null ? ZoneId.of("Europe/Moscow") : zoneId).toString();
            String prompt = buildPrompt(today, text);

            if (aiProperties.hasCloudLlm()) {
                ParsedEventData cloudParsed = extractViaCloudLlm(prompt);
                if (cloudParsed.hasAnyData()) {
                    return cloudParsed;
                }
                log.warn("Cloud LLM returned no structured data, trying Ollama fallback.");
            }

            if (aiProperties.hasOllama()) {
                return extractViaOllama(prompt);
            }
            return ParsedEventData.empty();
        } catch (Exception e) {
            log.warn("Structured parse failed: {}", e.getMessage());
            return ParsedEventData.empty();
        }
    }

    private String buildPrompt(String today, String text) {
        return """
                    Извлеки структуру календарного запроса.
                    Верни строго JSON, без markdown и комментариев.
                    today=%s
                    Схема:
                    {
                      "intent":"create_meeting|create_task|create_note|other",
                      "title":"string|null",
                      "date":"YYYY-MM-DD|null",
                      "time":"HH:mm|null",
                      "duration_minutes": integer|null
                    }
                    Правила:
                    - "двенадцать часов дня" => "12:00"
                    - title это только название события без даты, времени, длительности и без командных слов ("создай событие", "добавь встречу").
                    - Если нет данных, ставь null.
                    Текст: %s
                    """.formatted(today, text);
    }

    private ParsedEventData extractViaCloudLlm(String prompt) {
        RestClient client = RestClient.builder().baseUrl(aiProperties.llmBaseUrl().trim()).build();
        Map<String, Object> payload = Map.of(
                "model", aiProperties.llmModel().trim(),
                "temperature", 0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", "Ты извлекаешь структуру календарного запроса и возвращаешь только JSON."),
                        Map.of("role", "user", "content", prompt)
                )
        );

        Map<?, ?> response = client.post()
                .uri("/v1/chat/completions")
                .headers(headers -> headers.setBearerAuth(aiProperties.llmApiKey().trim()))
                .body(payload)
                .retrieve()
                .body(Map.class);

        if (response == null || !(response.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            return ParsedEventData.empty();
        }
        Object firstChoice = choices.get(0);
        if (!(firstChoice instanceof Map<?, ?> choiceMap)) {
            return ParsedEventData.empty();
        }
        Object messageObj = choiceMap.get("message");
        if (!(messageObj instanceof Map<?, ?> messageMap)) {
            return ParsedEventData.empty();
        }
        Object contentObj = messageMap.get("content");
        if (!(contentObj instanceof String raw) || raw.isBlank()) {
            return ParsedEventData.empty();
        }
        return parseStructuredJson(raw);
    }

    private ParsedEventData extractViaOllama(String prompt) {
        RestClient client = RestClient.builder().baseUrl(aiProperties.ollamaBaseUrl()).build();
        Map<String, Object> payload = Map.of(
                "model", aiProperties.ollamaModel(),
                "stream", false,
                "format", "json",
                "prompt", prompt,
                "options", Map.of("temperature", 0)
        );

        Map<?, ?> response = client.post()
                .uri("/api/generate")
                .body(payload)
                .retrieve()
                .body(Map.class);
        if (response == null || !(response.get("response") instanceof String raw) || raw.isBlank()) {
            return ParsedEventData.empty();
        }
        return parseStructuredJson(raw);
    }

    private ParsedEventData parseStructuredJson(String raw) {
        String json = extractJsonObject(raw);
        if (json == null || json.isBlank()) {
            return ParsedEventData.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            String intent = textOrNull(root.get("intent"));
            String title = textOrNull(root.get("title"));
            LocalDate date = parseDate(textOrNull(root.get("date")));
            LocalTime time = parseTime(textOrNull(root.get("time")));
            Integer duration = parseInteger(root.get("duration_minutes"));
            return new ParsedEventData(intent, title, date, time, duration);
        } catch (Exception e) {
            log.warn("Failed to parse structured JSON: {}", e.getMessage());
            return ParsedEventData.empty();
        }
    }

    private String extractJsonObject(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            return trimmed;
        }
        return trimmed.substring(start, end + 1);
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value) ? null : value.trim();
    }

    private LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private LocalTime parseTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm"));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Integer parseInteger(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isInt() || node.isLong()) {
            return node.asInt();
        }
        try {
            return Integer.parseInt(node.asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    public record ParsedEventData(
            String intent,
            String title,
            LocalDate date,
            LocalTime time,
            Integer durationMinutes
    ) {
        public static ParsedEventData empty() {
            return new ParsedEventData(null, null, null, null, null);
        }

        public boolean hasAnyData() {
            return title != null || date != null || time != null || durationMinutes != null || intent != null;
        }

        public boolean isCreateMeetingIntent() {
            return intent != null && intent.equalsIgnoreCase("create_meeting");
        }

        public boolean isCreateTaskIntent() {
            return intent != null && intent.equalsIgnoreCase("create_task");
        }

        public boolean isCreateNoteIntent() {
            return intent != null && intent.equalsIgnoreCase("create_note");
        }
    }
}
