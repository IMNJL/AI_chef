package com.aichef.controller;

import com.aichef.domain.enums.MeetingStatus;
import com.aichef.domain.model.CalendarDay;
import com.aichef.domain.model.Meeting;
import com.aichef.domain.model.User;
import com.aichef.repository.CalendarDayRepository;
import com.aichef.repository.MeetingRepository;
import com.aichef.service.GoogleCalendarService;
import com.aichef.service.GoogleOAuthService;
import com.aichef.service.MiniAppAuthService;
import com.aichef.util.TextNormalization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/miniapp/meetings")
public class MiniAppMeetingController {
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#([0-9a-fA-F]{6})$");
    private static final String DEFAULT_MEETING_COLOR = "#93c5fd";

    private final MiniAppAuthService miniAppAuthService;
    private final MeetingRepository meetingRepository;
    private final CalendarDayRepository calendarDayRepository;
    private final GoogleCalendarService googleCalendarService;
    private final GoogleOAuthService googleOAuthService;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestParam(value = "telegramId", required = false) Long telegramId
    ) {
        Optional<User> userOpt = miniAppAuthService.resolveUser(initData, telegramId);
        if (userOpt.isEmpty()) {
            log.warn("MiniApp meetings load unauthorized. telegramIdParam={}, hasInitData={}, from={}, to={}",
                    telegramId, initData != null && !initData.isBlank(), from, to);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }
        User user = userOpt.get();
        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate = LocalDate.parse(to);
        List<Meeting> meetings = meetingRepository
                .findByCalendarDay_UserAndCalendarDay_DayDateBetweenOrderByStartsAtAsc(user, fromDate, toDate);
        List<MeetingDto> result = meetings.stream()
                .filter(m -> m.getStatus() != MeetingStatus.CANCELED)
                .map(MeetingDto::from)
                .toList();
        log.info("MiniApp meetings loaded. userId={}, telegramId={}, from={}, to={}, count={}",
                user.getId(), user.getTelegramId(), fromDate, toDate, result.size());
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody MeetingUpdateRequest request,
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestParam(value = "telegramId", required = false) Long telegramId
    ) {
        Optional<User> userOpt = miniAppAuthService.resolveUser(initData, telegramId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }
        if (request.title() == null || request.title().isBlank()
                || request.startsAt() == null || request.endsAt() == null) {
            return ResponseEntity.badRequest().body("Missing required fields");
        }
        if (!request.endsAt().isAfter(request.startsAt())) {
            return ResponseEntity.badRequest().body("endsAt must be after startsAt");
        }
        String normalizedColor = normalizeHexColor(request.color());
        if (request.color() != null && normalizedColor == null) {
            return ResponseEntity.badRequest().body("Invalid color");
        }
        User user = userOpt.get();
        Meeting meeting = new Meeting();
        meeting.setTitle(TextNormalization.normalizeRussian(request.title().trim()));
        meeting.setStartsAt(request.startsAt());
        meeting.setEndsAt(request.endsAt());
        meeting.setLocation(request.location());
        meeting.setExternalLink(request.externalLink());
        meeting.setColor(normalizedColor == null ? DEFAULT_MEETING_COLOR : normalizedColor);
        meeting.setStatus(MeetingStatus.CONFIRMED);
        meeting.setCalendarDay(getOrCreateDay(user, request.startsAt().toLocalDate()));
        try {
            meetingRepository.save(meeting);
        } catch (DataIntegrityViolationException e) {
            log.error("MiniApp meeting create DB constraint error. userId={}, telegramId={}, title={}, startsAt={}, endsAt={}, color={}, error={}",
                    user.getId(), user.getTelegramId(), request.title(), request.startsAt(), request.endsAt(), normalizedColor, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("DB constraint error");
        } catch (Exception e) {
            log.error("MiniApp meeting create failed. userId={}, telegramId={}, title={}, startsAt={}, endsAt={}, color={}, error={}",
                    user.getId(), user.getTelegramId(), request.title(), request.startsAt(), request.endsAt(), normalizedColor, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal error");
        }

        // Create Google event only for users with explicit OAuth connection.
        if (googleOAuthService.isConnected(user)) {
            GoogleCalendarService.CreatedGoogleEvent googleEvent = googleCalendarService.createEvent(
                    user,
                    meeting.getTitle(),
                    meeting.getStartsAt(),
                    meeting.getEndsAt(),
                    meeting.getExternalLink(),
                    resolveZone(user)
            );
            if (googleEvent != null) {
                if (googleEvent.eventId() != null && !googleEvent.eventId().isBlank()) {
                    meeting.setGoogleEventId(googleEvent.eventId());
                }
                if ((meeting.getExternalLink() == null || meeting.getExternalLink().isBlank())
                        && googleEvent.htmlLink() != null && !googleEvent.htmlLink().isBlank()) {
                    meeting.setExternalLink(googleEvent.htmlLink());
                }
                meetingRepository.save(meeting);
            }
        }

        log.info("MiniApp meeting created. userId={}, telegramId={}, meetingId={}, startsAt={}, endsAt={}, color={}",
                user.getId(), user.getTelegramId(), meeting.getId(), meeting.getStartsAt(), meeting.getEndsAt(), meeting.getColor());
        return ResponseEntity.ok(MeetingDto.from(meeting));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(
            @PathVariable("id") UUID id,
            @RequestBody MeetingUpdateRequest request,
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestParam(value = "telegramId", required = false) Long telegramId
    ) {
        return updateInternal(id, request, initData, telegramId);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable("id") UUID id,
            @RequestBody MeetingUpdateRequest request,
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestParam(value = "telegramId", required = false) Long telegramId
    ) {
        return updateInternal(id, request, initData, telegramId);
    }

    private ResponseEntity<?> updateInternal(
            UUID id,
            MeetingUpdateRequest request,
            String initData,
            Long telegramId
    ) {
        Optional<User> userOpt = miniAppAuthService.resolveUser(initData, telegramId);
        if (userOpt.isEmpty()) {
            log.warn("MiniApp meeting update unauthorized. meetingId={}, telegramIdParam={}, hasInitData={}",
                    id, telegramId, initData != null && !initData.isBlank());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }
        User user = userOpt.get();
        Meeting meeting = meetingRepository.findByIdAndCalendarDay_User(id, user).orElse(null);
        if (meeting == null) {
            log.warn("MiniApp meeting update not found/forbidden. meetingId={}, userId={}, telegramId={}",
                    id, user.getId(), user.getTelegramId());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Meeting not found");
        }
        String normalizedColor = normalizeHexColor(request.color());
        if (request.color() != null && normalizedColor == null) {
            return ResponseEntity.badRequest().body("Invalid color");
        }

        if (request.title() != null) {
            String title = TextNormalization.normalizeRussian(request.title().trim());
            if (!title.isBlank()) {
                meeting.setTitle(title);
            }
        }
        if (request.startsAt() != null) {
            meeting.setStartsAt(request.startsAt());
            meeting.setCalendarDay(getOrCreateDay(user, request.startsAt().toLocalDate()));
        }
        if (request.endsAt() != null) {
            meeting.setEndsAt(request.endsAt());
        }
        if (request.location() != null) {
            meeting.setLocation(request.location());
        }
        if (request.externalLink() != null) {
            meeting.setExternalLink(request.externalLink());
        }
        if (request.color() != null) {
            meeting.setColor(normalizedColor);
        }
        OffsetDateTime startsAt = meeting.getStartsAt();
        OffsetDateTime endsAt = meeting.getEndsAt();
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            return ResponseEntity.badRequest().body("endsAt must be after startsAt");
        }
        try {
            meetingRepository.save(meeting);
        } catch (DataIntegrityViolationException e) {
            log.error("MiniApp meeting update DB constraint error. userId={}, telegramId={}, meetingId={}, startsAt={}, endsAt={}, color={}, error={}",
                    user.getId(), user.getTelegramId(), meeting.getId(), meeting.getStartsAt(), meeting.getEndsAt(), meeting.getColor(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("DB constraint error");
        } catch (Exception e) {
            log.error("MiniApp meeting update failed. userId={}, telegramId={}, meetingId={}, startsAt={}, endsAt={}, color={}, error={}",
                    user.getId(), user.getTelegramId(), meeting.getId(), meeting.getStartsAt(), meeting.getEndsAt(), meeting.getColor(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal error");
        }
        log.info("MiniApp meeting updated. userId={}, telegramId={}, meetingId={}, startsAt={}, endsAt={}, color={}",
                user.getId(), user.getTelegramId(), meeting.getId(), meeting.getStartsAt(), meeting.getEndsAt(), meeting.getColor());
        return ResponseEntity.ok(MeetingDto.from(meeting));
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
        User user = userOpt.get();
        Meeting meeting = meetingRepository.findByIdAndCalendarDay_User(id, user).orElse(null);
        if (meeting == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Meeting not found");
        }
        meeting.setStatus(MeetingStatus.CANCELED);
        try {
            meetingRepository.save(meeting);
        } catch (Exception e) {
            log.error("MiniApp meeting delete failed. userId={}, telegramId={}, meetingId={}, error={}",
                    user.getId(), user.getTelegramId(), meeting.getId(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal error");
        }
        log.info("MiniApp meeting deleted(canceled). userId={}, telegramId={}, meetingId={}",
                user.getId(), user.getTelegramId(), meeting.getId());
        return ResponseEntity.noContent().build();
    }

    private CalendarDay getOrCreateDay(User user, LocalDate dayDate) {
        return calendarDayRepository.findByUserAndDayDate(user, dayDate)
                .orElseGet(() -> {
                    CalendarDay day = new CalendarDay();
                    day.setUser(user);
                    day.setDayDate(dayDate);
                    day.setBusyLevel(0);
                    return calendarDayRepository.save(day);
                });
    }

    public record MeetingDto(
            UUID id,
            String title,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String location,
            String externalLink,
            String color
    ) {
        public static MeetingDto from(Meeting meeting) {
            return new MeetingDto(
                    meeting.getId(),
                    TextNormalization.normalizeRussian(meeting.getTitle()),
                    meeting.getStartsAt(),
                    meeting.getEndsAt(),
                    TextNormalization.normalizeRussian(meeting.getLocation()),
                    TextNormalization.normalizeRussian(meeting.getExternalLink()),
                    TextNormalization.normalizeRussian(meeting.getColor())
            );
        }
    }

    public record MeetingUpdateRequest(
            String title,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String location,
            String externalLink,
            String color
    ) {
    }

    private String normalizeHexColor(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        return HEX_COLOR_PATTERN.matcher(trimmed).matches() ? trimmed.toLowerCase() : null;
    }

    private ZoneId resolveZone(User user) {
        try {
            if (user == null || user.getTimezone() == null || user.getTimezone().isBlank()) {
                return ZoneId.of("Europe/Moscow");
            }
            return ZoneId.of(user.getTimezone());
        } catch (Exception ignored) {
            return ZoneId.of("Europe/Moscow");
        }
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<String> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.error("MiniApp meeting request type mismatch. message={}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid request format");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnhandled(Exception e) {
        log.error("MiniApp meeting controller unhandled error. message={}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal error");
    }
}
