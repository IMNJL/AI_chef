package com.aichef.service;

public interface VoiceTranscriptionService {
    VoiceTranscriptionResult transcribe(String fileId, String mimeType, Integer durationSec);
}
