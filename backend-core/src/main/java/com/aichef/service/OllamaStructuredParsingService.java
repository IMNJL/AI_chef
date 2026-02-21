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
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaStructuredParsingService {

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public ParsedEventData extractEventData(String text, ZoneId zoneId) {
        if (text == null || text.isBlank() || !aiProperties.hasOllama()) {
            return ParsedEventData.empty();
        }

        try {
            RestClient client = RestClient.builder().baseUrl(aiProperties.ollamaBaseUrl()).build();
            String today = LocalDate.now(zoneId == null ? ZoneId.of("Europe/Moscow") : zoneId).toString();
            String prompt = """
                    Извлеки структуру календарного запроса.
                    Верни строго JSON, без markdown и комментариев.
                    today=%s
                    Схема:
                    {
                      "intent":"create_meeting|create_task|other",
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

            JsonNode root = objectMapper.readTree(raw);
            String intent = textOrNull(root.get("intent"));
            String title = textOrNull(root.get("title"));
            LocalDate date = parseDate(textOrNull(root.get("date")));
            LocalTime time = parseTime(textOrNull(root.get("time")));
            Integer duration = parseInteger(root.get("duration_minutes"));
            return new ParsedEventData(intent, title, date, time, duration);
        } catch (Exception e) {
            log.warn("Ollama structured parse failed: {}", e.getMessage());
            return ParsedEventData.empty();
        }
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
    }
}
