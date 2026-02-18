package com.aichef.service;

import com.aichef.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class FallbackVoiceTranscriptionService implements VoiceTranscriptionService {

    private final AiProperties aiProperties;
    private final GeminiVoiceTranscriptionService geminiVoiceTranscriptionService;
    private final LocalWhisperVoiceTranscriptionService localWhisperVoiceTranscriptionService;

    @Override
    public VoiceTranscriptionResult transcribe(String fileId, String mimeType, Integer durationSec) {
        Exception geminiError = null;

        if (aiProperties.hasGeminiKey()) {
            try {
                return geminiVoiceTranscriptionService.transcribe(fileId, mimeType, durationSec);
            } catch (Exception e) {
                geminiError = e;
                log.warn("Gemini STT failed, fallback to local Whisper. error={}", e.getMessage());
            }
        }

        if (aiProperties.hasWhisperCommand()) {
            return localWhisperVoiceTranscriptionService.transcribe(fileId, mimeType, durationSec);
        }

        if (geminiError != null) {
            throw new IllegalStateException("Voice transcription failed and APP_WHISPER_COMMAND is not configured.", geminiError);
        }
        throw new IllegalStateException("Voice transcription is unavailable. Configure APP_GEMINI_API_KEY or APP_WHISPER_COMMAND.");
    }
}
