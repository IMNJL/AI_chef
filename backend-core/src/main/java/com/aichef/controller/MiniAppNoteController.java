package com.aichef.controller;

import com.aichef.domain.model.Note;
import com.aichef.domain.model.User;
import com.aichef.repository.NoteRepository;
import com.aichef.service.MiniAppAuthService;
import com.aichef.util.TextNormalization;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/miniapp/notes")
public class MiniAppNoteController {

    private final MiniAppAuthService miniAppAuthService;
    private final NoteRepository noteRepository;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestParam(value = "telegramId", required = false) Long telegramId
    ) {
        Optional<User> userOpt = miniAppAuthService.resolveUser(initData, telegramId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        List<NoteDto> notes = noteRepository.findTop20ByUserAndArchivedFalseOrderByUpdatedAtDesc(userOpt.get())
                .stream()
                .map(NoteDto::from)
                .toList();

        return ResponseEntity.ok(notes);
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody NoteCreateRequest request,
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestParam(value = "telegramId", required = false) Long telegramId
    ) {
        Optional<User> userOpt = miniAppAuthService.resolveUser(initData, telegramId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        if (request.title() == null || request.title().isBlank()
                || request.content() == null || request.content().isBlank()) {
            return ResponseEntity.badRequest().body("Missing required fields");
        }

        Note note = new Note();
        note.setUser(userOpt.get());
        note.setTitle(TextNormalization.normalizeRussian(request.title().trim()));
        note.setContent(TextNormalization.normalizeRussian(request.content().trim()));
        note.setArchived(false);
        noteRepository.save(note);

        return ResponseEntity.ok(NoteDto.from(note));
    }

    public record NoteCreateRequest(String title, String content) {
    }

    public record NoteDto(
            UUID id,
            String title,
            String content,
            OffsetDateTime updatedAt
    ) {
        public static NoteDto from(Note note) {
            return new NoteDto(
                    note.getId(),
                    TextNormalization.normalizeRussian(note.getTitle()),
                    TextNormalization.normalizeRussian(note.getContent()),
                    note.getUpdatedAt()
            );
        }
    }
}
