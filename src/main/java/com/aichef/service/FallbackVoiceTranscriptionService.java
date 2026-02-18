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
    private final LocalVoskVoiceTranscriptionService localVoskVoiceTranscriptionService;
    private final GeminiVoiceTranscriptionService geminiVoiceTranscriptionService;
    private final LocalWhisperVoiceTranscriptionService localWhisperVoiceTranscriptionService;

    @Override
    public VoiceTranscriptionResult transcribe(String fileId, String mimeType, Integer durationSec) {
        Exception localError = null;

        if (aiProperties.hasVoskModelPath()) {
            try {
                VoiceTranscriptionResult result = localVoskVoiceTranscriptionService.transcribe(fileId, mimeType, durationSec);
                log.info("STT engine=Vosk fileId={}", fileId);
                return result;
            } catch (Exception e) {
                localError = e;
                log.warn("Vosk STT failed, fallback to Whisper/Gemini. error={}", e.getMessage());
            }
        }

        if (aiProperties.hasWhisperCommand()) {
            try {
                VoiceTranscriptionResult result = localWhisperVoiceTranscriptionService.transcribe(fileId, mimeType, durationSec);
                log.info("STT engine=Whisper fileId={}", fileId);
                return result;
            } catch (Exception e) {
                localError = e;
                log.warn("Whisper STT failed, fallback to Gemini. error={}", e.getMessage());
            }
        }

        if (aiProperties.hasGeminiKey()) {
            try {
                VoiceTranscriptionResult result = geminiVoiceTranscriptionService.transcribe(fileId, mimeType, durationSec);
                log.info("STT engine=Gemini fileId={}", fileId);
                return result;
            } catch (Exception e) {
                if (localError == null) {
                    localError = e;
                }
                log.warn("Gemini STT failed. error={}", e.getMessage());
            }
        }

        if (localError != null) {
            throw new IllegalStateException("Voice transcription failed in all configured engines.", localError);
        }
        throw new IllegalStateException("Voice transcription is unavailable. Configure APP_VOSK_MODEL_PATH, APP_WHISPER_COMMAND or APP_GEMINI_API_KEY.");
    }
}
