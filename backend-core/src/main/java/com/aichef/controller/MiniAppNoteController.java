package com.aichef.controller;

import com.aichef.domain.model.Note;
import com.aichef.domain.model.User;
import com.aichef.repository.NoteRepository;
import com.aichef.service.MiniAppAuthService;
import com.aichef.util.TextNormalization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@Slf4j
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
            log.warn("MiniApp notes load unauthorized. telegramIdParam={}, hasInitData={}",
                    telegramId, initData != null && !initData.isBlank());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        User user = userOpt.get();
        List<NoteDto> notes = noteRepository.findTop20ByUserAndArchivedFalseOrderByUpdatedAtDesc(user)
                .stream()
                .map(NoteDto::from)
                .toList();
        log.info("MiniApp notes loaded. userId={}, telegramId={}, count={}",
                user.getId(), user.getTelegramId(), notes.size());

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

        if (request.title() == null || request.title().isBlank()) {
            return ResponseEntity.badRequest().body("Missing required fields");
        }

        Note note = new Note();
        note.setUser(userOpt.get());
        note.setTitle(TextNormalization.normalizeRussian(request.title().trim()));
        String rawContent = request.content() == null ? "" : request.content();
        note.setContent(TextNormalization.normalizeRussian(rawContent.trim()));
        note.setArchived(false);
        noteRepository.save(note);

        return ResponseEntity.ok(NoteDto.from(note));
    }

    public record NoteCreateRequest(String title, String content) {
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestParam(value = "telegramId", required = false) Long telegramId
    ) {
        Optional<User> userOpt = miniAppAuthService.resolveUser(initData, telegramId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }
        Note note = noteRepository.findByIdAndUser(id, userOpt.get()).orElse(null);
        if (note == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Note not found");
        }
        note.setArchived(true);
        noteRepository.save(note);
        return ResponseEntity.noContent().build();
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
