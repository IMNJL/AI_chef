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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/miniapp/meetings")
public class MiniAppMeetingController {

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
        User user = userOpt.get();
        Meeting meeting = new Meeting();
        meeting.setTitle(TextNormalization.normalizeRussian(request.title().trim()));
        meeting.setStartsAt(request.startsAt());
        meeting.setEndsAt(request.endsAt());
        meeting.setLocation(request.location());
        meeting.setExternalLink(request.externalLink());
        meeting.setStatus(MeetingStatus.CONFIRMED);
        meeting.setCalendarDay(getOrCreateDay(user, request.startsAt().toLocalDate()));
        meetingRepository.save(meeting);
        return ResponseEntity.ok(MeetingDto.from(meeting));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable("id") UUID id,
            @RequestBody MeetingUpdateRequest request,
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
        meetingRepository.save(meeting);
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
            String externalLink
    ) {
        public static MeetingDto from(Meeting meeting) {
            return new MeetingDto(
                    meeting.getId(),
                    TextNormalization.normalizeRussian(meeting.getTitle()),
                    meeting.getStartsAt(),
                    meeting.getEndsAt(),
                    TextNormalization.normalizeRussian(meeting.getLocation()),
                    TextNormalization.normalizeRussian(meeting.getExternalLink())
            );
        }
    }

    public record MeetingUpdateRequest(
            String title,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            String location,
            String externalLink
    ) {
    }
}
