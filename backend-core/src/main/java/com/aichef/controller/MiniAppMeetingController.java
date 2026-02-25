package com.aichef.controller;

import com.aichef.domain.enums.MeetingStatus;
import com.aichef.domain.model.CalendarDay;
import com.aichef.domain.model.Meeting;
import com.aichef.domain.model.User;
import com.aichef.repository.CalendarDayRepository;
import com.aichef.repository.MeetingRepository;
import com.aichef.service.MiniAppAuthService;
import com.aichef.util.TextNormalization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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
        meetingRepository.save(meeting);
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
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }
        User user = userOpt.get();
        Meeting meeting = meetingRepository.findById(id).orElse(null);
        if (meeting == null || meeting.getCalendarDay() == null
                || meeting.getCalendarDay().getUser() == null
                || !meeting.getCalendarDay().getUser().getId().equals(user.getId())) {
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
        meetingRepository.save(meeting);
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
        Meeting meeting = meetingRepository.findById(id).orElse(null);
        if (meeting == null || meeting.getCalendarDay() == null
                || meeting.getCalendarDay().getUser() == null
                || !meeting.getCalendarDay().getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Meeting not found");
        }
        meeting.setStatus(MeetingStatus.CANCELED);
        meetingRepository.save(meeting);
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
}
