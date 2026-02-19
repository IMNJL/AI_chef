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
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalWhisperVoiceTranscriptionService implements VoiceTranscriptionService {

    private static final long DOWNLOAD_ERROR_COOLDOWN_SEC = 30 * 60;
    private static final long WHISPER_TIMEOUT_SEC = 5 * 60;
    private static final int MIN_REASONABLE_TEXT_LENGTH = 12;
    private static final int MIN_LETTER_COUNT = 6;

    private final RestClient telegramRestClient;
    private final TelegramProperties telegramProperties;
    private final AiProperties aiProperties;
    private volatile long blockedUntilEpochSec = 0;

    @Override
    public VoiceTranscriptionResult transcribe(String fileId, String mimeType, Integer durationSec) {
        if (!aiProperties.hasWhisperCommand()) {
            throw new IllegalStateException("APP_WHISPER_COMMAND is empty. Local whisper is disabled.");
        }

        String filePath = resolveTelegramFilePath(fileId);
        byte[] voiceBytes = downloadTelegramFile(filePath);
        String primaryModel = resolvePrimaryModel();
        String fallbackModel = normalizeModelValue(aiProperties.whisperFallbackModel());
        String transcribedText = transcribeWithWhisperTwoStage(voiceBytes, primaryModel, fallbackModel, fileId);
        log.info("Voice transcribed by local Whisper. fileId={}, mimeType={}, textLength={}, text={}",
                fileId, mimeType, transcribedText.length(), compactForLog(transcribedText));
        String telegramFileUrl = telegramProperties.apiBase() + "/file/bot" + telegramProperties.botToken() + "/" + filePath;

        return new VoiceTranscriptionResult(transcribedText, telegramFileUrl, mimeType, durationSec);
    }

    private String transcribeWithWhisperTwoStage(byte[] audioBytes, String primaryModel, String fallbackModel, String fileId) {
        String firstPass;
        try {
            firstPass = transcribeWithWhisperCli(audioBytes, primaryModel);
        } catch (Exception firstError) {
            if (canUseFallbackModel(primaryModel, fallbackModel)) {
                log.warn("Whisper fast pass failed on model={}, retrying with fallback model={}. fileId={}, error={}",
                        primaryModel, fallbackModel, fileId, firstError.getMessage());
                return transcribeWithWhisperCli(audioBytes, fallbackModel);
            }
            throw firstError;
        }

        if (!shouldRetryWithFallback(firstPass) || !canUseFallbackModel(primaryModel, fallbackModel)) {
            return firstPass;
        }

        log.info("Whisper fallback triggered due to low-quality first pass. fileId={}, primaryModel={}, fallbackModel={}, firstPassText={}",
                fileId, primaryModel, fallbackModel, compactForLog(firstPass));
        try {
            return transcribeWithWhisperCli(audioBytes, fallbackModel);
        } catch (Exception fallbackError) {
            log.warn("Whisper fallback failed, using first pass result. fileId={}, fallbackModel={}, error={}",
                    fileId, fallbackModel, fallbackError.getMessage());
            return firstPass;
        }
    }

    private String transcribeWithWhisperCli(byte[] audioBytes, String model) {
        long nowEpochSec = System.currentTimeMillis() / 1000;
        if (nowEpochSec < blockedUntilEpochSec) {
            long waitSec = blockedUntilEpochSec - nowEpochSec;
            throw new IllegalStateException("Whisper model download is temporarily blocked after repeated checksum failures. "
                    + "Retry in ~" + waitSec + "s or provide local model file via APP_WHISPER_MODEL=/absolute/path/to/model.pt");
        }

        Path workDir = null;
        Path modelCachePath = resolveModelCachePath(model);
        try {
            workDir = Files.createTempDirectory("whisper-stt-");
            Path input = workDir.resolve("voice_input.ogg");
            Files.write(input, audioBytes);

            String cmd = aiProperties.whisperCommand()
                    .replace("{input}", input.toAbsolutePath().toString())
                    .replace("{model}", model)
                    .replace("{output_dir}", workDir.toAbsolutePath().toString());

            logModelCacheStatus("before_run", model, modelCachePath);
            CommandResult firstAttempt = runWhisper(cmd, workDir, model, modelCachePath);
            logModelCacheStatus("after_run", model, modelCachePath);
            if (firstAttempt.exitCode() != 0) {
                if (isModelChecksumMismatch(firstAttempt.stderr())) {
                    clearCachedModel(model);
                    log.warn("Corrupted Whisper cache detected for model={}, retrying transcription once.", model);
                    logModelCacheStatus("before_retry", model, modelCachePath);
                    CommandResult secondAttempt = runWhisper(cmd, workDir, model, modelCachePath);
                    logModelCacheStatus("after_retry", model, modelCachePath);
                    if (secondAttempt.exitCode() != 0) {
                        blockedUntilEpochSec = (System.currentTimeMillis() / 1000) + DOWNLOAD_ERROR_COOLDOWN_SEC;
                        throw new IllegalStateException("Whisper command failed after cache reset. stderr="
                                + secondAttempt.stderr() + ", stdout=" + secondAttempt.stdout());
                    }
                } else {
                    throw new IllegalStateException("Whisper command failed. stderr=" + firstAttempt.stderr()
                            + ", stdout=" + firstAttempt.stdout());
                }
            }

            Path txt = workDir.resolve("voice_input.txt");
            if (!Files.exists(txt)) {
                txt = Files.list(workDir)
                        .filter(p -> p.getFileName().toString().endsWith(".txt"))
                        .findFirst()
                        .orElse(null);
            }
            if (txt == null || !Files.exists(txt)) {
                throw new IllegalStateException("Whisper output text file not found.");
            }

            String text = Files.readString(txt, StandardCharsets.UTF_8).trim();
            if (text.isBlank()) {
                throw new IllegalStateException("Whisper returned empty text.");
            }
            return text;
        } catch (Exception e) {
            throw new IllegalStateException("Local Whisper transcription failed for model '" + model + "': " + e.getMessage(), e);
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

    private String resolvePrimaryModel() {
        String configured = normalizeModelValue(aiProperties.whisperModel());
        return configured == null ? "small" : configured;
    }

    private String normalizeModelValue(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        return model.trim();
    }

    private boolean canUseFallbackModel(String primaryModel, String fallbackModel) {
        return fallbackModel != null && !fallbackModel.equals(primaryModel);
    }

    private boolean shouldRetryWithFallback(String text) {
        if (text == null) {
            return true;
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.isBlank()) {
            return true;
        }
        if (compact.length() < MIN_REASONABLE_TEXT_LENGTH) {
            return true;
        }

        long letters = compact.chars().filter(Character::isLetter).count();
        if (letters < MIN_LETTER_COUNT) {
            return true;
        }

        long spaces = compact.chars().filter(ch -> ch == ' ').count();
        long words = spaces + 1;
        return words <= 2 && compact.length() <= 16;
    }

    private CommandResult runWhisper(String cmd, Path workDir, String model, Path modelCachePath) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/bin/zsh", "-lc", cmd)
                .directory(workDir.toFile())
                .start();

        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + WHISPER_TIMEOUT_SEC * 1000;
        long lastLoggedSize = -1;
        boolean finished = false;
        while (System.currentTimeMillis() < deadline) {
            if (process.waitFor(2, TimeUnit.SECONDS)) {
                finished = true;
                break;
            }
            if (modelCachePath != null) {
                long size = modelFileSize(modelCachePath);
                if (size >= 0 && size != lastLoggedSize) {
                    lastLoggedSize = size;
                    log.debug("Whisper download progress. model={}, cacheFile={}, size={} MB",
                            model, modelCachePath, String.format(Locale.ROOT, "%.2f", size / (1024.0 * 1024.0)));
                } else if (size < 0) {
                    log.debug("Whisper download progress. model={}, cacheFile={} not present yet", model, modelCachePath);
                }
            } else {
                log.debug("Whisper run in progress. model={}, custom model path/no cache tracking", model);
            }
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Whisper command timeout after " + Duration.ofSeconds(WHISPER_TIMEOUT_SEC));
        }

        log.debug("Whisper run finished. model={}, exitCode={}, durationSec={}",
                model, process.exitValue(), (System.currentTimeMillis() - startedAt) / 1000);
        return new CommandResult(process.exitValue(), stdout, stderr);
    }

    private boolean isModelChecksumMismatch(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return false;
        }
        String msg = stderr.toLowerCase(Locale.ROOT);
        return msg.contains("sha256 checksum does not match");
    }

    private void clearCachedModel(String model) {
        if (model == null || model.isBlank() || model.contains("/") || model.contains("\\")) {
            return;
        }
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return;
        }

        Path modelFile = Paths.get(userHome, ".cache", "whisper", model + ".pt");
        try {
            if (Files.deleteIfExists(modelFile)) {
                log.info("Deleted Whisper model cache file {}", modelFile);
            }
        } catch (IOException e) {
            log.warn("Failed to delete Whisper model cache file {}: {}", modelFile, e.getMessage());
        }
    }

    private Path resolveModelCachePath(String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        if (model.contains("/") || model.contains("\\") || model.endsWith(".pt")) {
            return null;
        }
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return null;
        }
        return Paths.get(userHome, ".cache", "whisper", model + ".pt");
    }

    private long modelFileSize(Path path) {
        if (path == null || !Files.exists(path)) {
            return -1;
        }
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    private void logModelCacheStatus(String phase, String model, Path modelCachePath) {
        if (!log.isDebugEnabled()) {
            return;
        }
        if (modelCachePath == null) {
            log.debug("Whisper {}. model={}, custom model path/no cache file", phase, model);
            return;
        }
        long size = modelFileSize(modelCachePath);
        if (size < 0) {
            log.debug("Whisper {}. model={}, cacheFile={} not found", phase, model, modelCachePath);
        } else {
            log.debug("Whisper {}. model={}, cacheFile={}, size={} MB",
                    phase, model, modelCachePath, String.format(Locale.ROOT, "%.2f", size / (1024.0 * 1024.0)));
        }
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

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
