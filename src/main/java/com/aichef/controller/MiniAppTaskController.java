package com.aichef.controller;

import com.aichef.domain.enums.PriorityLevel;
import com.aichef.domain.model.CalendarDay;
import com.aichef.domain.model.TaskItem;
import com.aichef.domain.model.User;
import com.aichef.repository.CalendarDayRepository;
import com.aichef.repository.TaskItemRepository;
import com.aichef.service.MiniAppAuthService;
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
@RequestMapping("/api/miniapp/tasks")
public class MiniAppTaskController {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Moscow");

    private final MiniAppAuthService miniAppAuthService;
    private final TaskItemRepository taskItemRepository;
    private final CalendarDayRepository calendarDayRepository;

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestParam(value = "telegramId", required = false) Long telegramId
    ) {
        Optional<User> userOpt = miniAppAuthService.resolveUser(initData, telegramId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        List<TaskDto> tasks;
        User user = userOpt.get();
        if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
            LocalDate fromDate = LocalDate.parse(from);
            LocalDate toDate = LocalDate.parse(to);
            tasks = taskItemRepository
                    .findByCalendarDay_UserAndCalendarDay_DayDateBetweenOrderByDueAtAsc(user, fromDate, toDate)
                    .stream()
                    .map(TaskDto::from)
                    .toList();
        } else {
            tasks = taskItemRepository
                    .findTop100ByCalendarDay_UserOrderByDueAtAsc(user)
                    .stream()
                    .map(TaskDto::from)
                    .toList();
        }

        return ResponseEntity.ok(tasks);
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestBody TaskCreateRequest request,
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

        User user = userOpt.get();
        OffsetDateTime dueAt = request.dueAt();
        LocalDate day = dueAt != null
                ? dueAt.atZoneSameInstant(DEFAULT_ZONE).toLocalDate()
                : OffsetDateTime.now(DEFAULT_ZONE).toLocalDate();

        CalendarDay calendarDay = getOrCreateDay(user, day);

        TaskItem task = new TaskItem();
        task.setCalendarDay(calendarDay);
        task.setTitle(request.title().trim());
        task.setDueAt(dueAt);
        task.setCompleted(false);
        task.setPriority(resolvePriority(request.priority()));
        taskItemRepository.save(task);

        return ResponseEntity.ok(TaskDto.from(task));
    }

    private PriorityLevel resolvePriority(String value) {
        if (value == null || value.isBlank()) {
            return PriorityLevel.MEDIUM;
        }
        try {
            return PriorityLevel.valueOf(value.trim().toUpperCase());
        } catch (Exception ignored) {
            return PriorityLevel.MEDIUM;
        }
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

    public record TaskCreateRequest(String title, String priority, OffsetDateTime dueAt) {
    }

    public record TaskDto(
            UUID id,
            String title,
            String priority,
            boolean completed,
            OffsetDateTime dueAt
    ) {
        public static TaskDto from(TaskItem task) {
            return new TaskDto(
                    task.getId(),
                    task.getTitle(),
                    task.getPriority() == null ? "MEDIUM" : task.getPriority().name(),
                    task.isCompleted(),
                    task.getDueAt()
            );
        }
    }
}
