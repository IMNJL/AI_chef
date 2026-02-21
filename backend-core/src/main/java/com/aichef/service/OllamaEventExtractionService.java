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
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaEventExtractionService {

    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public EventExtraction extract(String text, ZoneId zoneId) {
        if (text == null || text.isBlank() || !aiProperties.hasOllama()) {
            return EventExtraction.empty();
        }

        try {
            RestClient client = RestClient.builder().baseUrl(aiProperties.ollamaBaseUrl()).build();
            String today = LocalDate.now(zoneId).format(DateTimeFormatter.ISO_LOCAL_DATE);
            String prompt = """
                    Ты извлекаешь структуру из пользовательской команды календаря.
                    Верни ТОЛЬКО JSON без пояснений.
                    today=%s
                    Формат JSON:
                    {
                      "action":"create_meeting|create_task|other",
                      "title":"...",
                      "date":"YYYY-MM-DD|null",
                      "time":"HH:mm|null",
                      "duration_minutes": number|null
                    }
                    Правила:
                    - Если в тексте встреча/созвон/митинг -> action=create_meeting.
                    - Если задача/сделать/надо -> action=create_task.
                    - date/time должны быть нормализованы.
                    - Если в тексте "двенадцать часов дня", это 12:00.
                    - Если поле не найдено, верни null.
                    Текст:
                    %s
                    """.formatted(today, text);

            Map<String, Object> payload = Map.of(
                    "model", aiProperties.ollamaModel(),
                    "stream", false,
                    "format", "json",
                    "prompt", prompt
            );

            Map<?, ?> response = client.post()
                    .uri("/api/generate")
                    .body(payload)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("response") instanceof String raw) || raw.isBlank()) {
                return EventExtraction.empty();
            }

            JsonNode node = objectMapper.readTree(raw);
            String action = textOrNull(node.get("action"));
            String title = textOrNull(node.get("title"));
            LocalDate date = parseDate(textOrNull(node.get("date")));
            LocalTime time = parseTime(textOrNull(node.get("time")));
            Integer duration = parseInt(node.get("duration_minutes"));
            if (duration != null && duration <= 0) {
                duration = null;
            }
            return new EventExtraction(action, title, date, time, duration);
        } catch (Exception e) {
            log.warn("Ollama extraction failed: {}", e.getMessage());
            return EventExtraction.empty();
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
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalTime parseTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer parseInt(JsonNode node) {
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

    public record EventExtraction(
            String action,
            String title,
            LocalDate date,
            LocalTime time,
            Integer durationMinutes
    ) {
        public static EventExtraction empty() {
            return new EventExtraction(null, null, null, null, null);
        }
    }
}
