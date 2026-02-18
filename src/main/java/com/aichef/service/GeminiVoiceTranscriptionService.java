package com.aichef.service;

import com.aichef.config.AiProperties;
import com.aichef.config.TelegramProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiVoiceTranscriptionService implements VoiceTranscriptionService {

    private final RestClient telegramRestClient;
    private final TelegramProperties telegramProperties;
    private final AiProperties aiProperties;

    @Override
    public VoiceTranscriptionResult transcribe(String fileId, String mimeType, Integer durationSec) {
        if (!aiProperties.hasGeminiKey()) {
            throw new IllegalStateException("APP_GEMINI_API_KEY is empty. Voice transcription is disabled.");
        }

        String filePath = resolveTelegramFilePath(fileId);
        byte[] voiceBytes = downloadTelegramFile(filePath);
        String transcribedText = transcribeWithGemini(voiceBytes, mimeType);
        String telegramFileUrl = telegramProperties.apiBase() + "/file/bot" + telegramProperties.botToken() + "/" + filePath;

        return new VoiceTranscriptionResult(transcribedText, telegramFileUrl, mimeType, durationSec);
    }

    private String resolveTelegramFilePath(String fileId) {
        try {
            Map<?, ?> response = telegramRestClient.get()
                    .uri("/bot{token}/getFile?file_id={fileId}", telegramProperties.botToken(), fileId)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !Boolean.TRUE.equals(response.get("ok"))) {
                throw new IllegalStateException("Telegram getFile failed: " + response);
            }
            Object resultObj = response.get("result");
            if (!(resultObj instanceof Map<?, ?> resultMap)) {
                throw new IllegalStateException("Telegram getFile has no result.");
            }
            Object filePath = resultMap.get("file_path");
            if (!(filePath instanceof String fp) || fp.isBlank()) {
                throw new IllegalStateException("Telegram file_path is empty.");
            }
            return fp;
        } catch (RestClientException e) {
            throw new IllegalStateException("Telegram getFile error: " + e.getMessage(), e);
        }
    }

    private byte[] downloadTelegramFile(String filePath) {
        try {
            byte[] bytes = telegramRestClient.get()
                    .uri("/file/bot{token}/{filePath}", telegramProperties.botToken(), filePath)
                    .retrieve()
                    .body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("Downloaded voice file is empty.");
            }
            return bytes;
        } catch (RestClientException e) {
            throw new IllegalStateException("Telegram file download error: " + e.getMessage(), e);
        }
    }

    private String transcribeWithGemini(byte[] bytes, String mimeType) {
        RestClient geminiClient = RestClient.builder()
                .baseUrl(resolveBaseUrl())
                .build();

        Map<String, Object> payload = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", "Transcribe this audio message into plain Russian text only."),
                                Map.of("inline_data", Map.of(
                                        "mime_type", (mimeType == null || mimeType.isBlank()) ? "audio/ogg" : mimeType,
                                        "data", Base64.getEncoder().encodeToString(bytes)
                                ))
                        ))
                ),
                "generationConfig", Map.of("temperature", 0.0)
        );

        try {
            Map<?, ?> response = geminiClient.post()
                    .uri(uri -> uri
                            .path("/v1beta/models/{model}:generateContent")
                            .queryParam("key", aiProperties.geminiApiKey())
                            .build(resolveModel()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(Map.class);

            String text = extractTextResponse(response);
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("Gemini STT returned empty result.");
            }

            log.info("Voice transcribed by Gemini. mimeType={}, textLength={}", mimeType, text.length());
            return text.trim();
        } catch (RestClientException e) {
            throw new IllegalStateException("Gemini STT error: " + e.getMessage(), e);
        }
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
            if (part instanceof Map<?, ?> partMap) {
                Object text = partMap.get("text");
                if (text instanceof String s) {
                    sb.append(s);
                }
            }
        }
        return sb.toString();
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
