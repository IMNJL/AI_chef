package com.aichef.service;

import com.aichef.config.AiProperties;
import com.aichef.config.TelegramProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalVoskVoiceTranscriptionService implements VoiceTranscriptionService {

    private static final long VOSK_TIMEOUT_SEC = 10 * 60;

    private final RestClient telegramRestClient;
    private final TelegramProperties telegramProperties;
    private final AiProperties aiProperties;

    @Override
    public VoiceTranscriptionResult transcribe(String fileId, String mimeType, Integer durationSec) {
        if (!aiProperties.hasVoskModelPath()) {
            throw new IllegalStateException("APP_VOSK_MODEL_PATH is empty. Local Vosk is disabled.");
        }

        String filePath = resolveTelegramFilePath(fileId);
        byte[] voiceBytes = downloadTelegramFile(filePath);
        String text = transcribeWithVosk(fileId, voiceBytes);
        log.info("Voice transcribed by local Vosk. fileId={}, mimeType={}, textLength={}, text={}",
                fileId, mimeType, text.length(), compactForLog(text));
        String telegramFileUrl = telegramProperties.apiBase() + "/file/bot" + telegramProperties.botToken() + "/" + filePath;
        return new VoiceTranscriptionResult(text, telegramFileUrl, mimeType, durationSec);
    }

    private String transcribeWithVosk(String fileId, byte[] audioBytes) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("vosk-stt-");
            Path input = workDir.resolve("voice_input.ogg");
            Files.write(input, audioBytes);
            Path script = Path.of("scripts", "vosk_transcribe.py").toAbsolutePath();
            String python = resolvePythonExecutable();
            String modelPath = Path.of(aiProperties.voskModelPath().trim()).toAbsolutePath().toString();

            Process process = new ProcessBuilder(
                    python,
                    script.toString(),
                    "--model",
                    modelPath,
                    "--input",
                    input.toAbsolutePath().toString()
            ).directory(Path.of(".").toFile()).start();

            if (!process.waitFor(VOSK_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Vosk transcription timeout after " + VOSK_TIMEOUT_SEC + "s");
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Vosk command failed. stderr=" + stderr);
            }
            if (stdout.isBlank()) {
                throw new IllegalStateException("Vosk returned empty text. stderr=" + stderr);
            }
            return stdout;
        } catch (Exception e) {
            throw new IllegalStateException("Local Vosk transcription failed for fileId=" + fileId + ": " + e.getMessage(), e);
        } finally {
            if (workDir != null) {
                try {
                    Files.walk(workDir)
                            .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                }
                            });
                } catch (IOException ignored) {
                }
            }
        }
    }

    private String resolvePythonExecutable() {
        String configured = aiProperties.voskPython();
        if (configured == null || configured.isBlank()) {
            return ".venv/bin/python";
        }
        return configured.trim();
    }

    private String compactForLog(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim();
        int limit = 500;
        if (compact.length() <= limit) {
            return compact;
        }
        return compact.substring(0, limit) + "...";
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
}
