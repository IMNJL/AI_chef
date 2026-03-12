package com.aichef.controller;

import com.aichef.domain.enums.Gender;
import com.aichef.domain.model.User;
import com.aichef.domain.model.UserGoogleConnection;
import com.aichef.repository.UserRepository;
import com.aichef.repository.UserGoogleConnectionRepository;
import com.aichef.service.MiniAppAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/api/miniapp")
public class MiniAppMeController {

    private final MiniAppAuthService miniAppAuthService;
    private final UserRepository userRepository;
    private final UserGoogleConnectionRepository userGoogleConnectionRepository;

    @GetMapping("/me")
    public ResponseEntity<?> me(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestParam(value = "telegramId", required = false) Long telegramId
    ) {
        Optional<User> userOpt = miniAppAuthService.resolveUser(initData, telegramId);
        if (userOpt.isEmpty()) {
            log.warn("MiniApp profile load unauthorized. telegramIdParam={}, hasInitData={}",
                    telegramId, initData != null && !initData.isBlank());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        User user = userOpt.get();
        Gender gender = user.getGender() == null ? Gender.UNKNOWN : user.getGender();
        log.info("MiniApp profile loaded. userId={}, telegramId={}, gender={}",
                user.getId(), user.getTelegramId(), gender);
        UserGoogleConnection conn = userGoogleConnectionRepository.findByUser(user).orElse(null);
        boolean googleConnected = conn != null
                && ((conn.getRefreshToken() != null && !conn.getRefreshToken().isBlank())
                || (conn.getAccessToken() != null && !conn.getAccessToken().isBlank()));
        boolean icsConnected = conn != null && conn.getIcsToken() != null && !conn.getIcsToken().isBlank();
        return ResponseEntity.ok(new MeResponse(
                user.getId(),
                user.getTelegramId(),
                gender,
                toTitlePrefix(gender),
                user.getTimezone(),
                new CalendarConnections(true, googleConnected, icsConnected)
        ));
    }

    @PatchMapping("/me/timezone")
    public ResponseEntity<?> updateTimezone(
            @RequestBody TimezoneUpdateRequest request,
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestParam(value = "telegramId", required = false) Long telegramId
    ) {
        Optional<User> userOpt = miniAppAuthService.resolveUser(initData, telegramId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }
        String timezone = request == null || request.timezone() == null ? "" : request.timezone().trim();
        if (timezone.isBlank()) {
            return ResponseEntity.badRequest().body("timezone is required");
        }
        try {
            ZoneId.of(timezone);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid timezone");
        }
        User user = userOpt.get();
        user.setTimezone(timezone);
        userRepository.save(user);
        return ResponseEntity.ok(new TimezoneUpdateResponse(timezone));
    }

    private String toTitlePrefix(Gender gender) {
        if (gender == Gender.FEMALE) {
            return "Ms";
        }
        return "Mr";
    }

    public record MeResponse(
            UUID id,
            Long telegramId,
            Gender gender,
            String titlePrefix,
            String timezone,
            CalendarConnections calendars
    ) {
    }

    public record CalendarConnections(
            boolean internal,
            boolean google,
            boolean ical
    ) {
    }

    public record TimezoneUpdateRequest(String timezone) {
    }

    public record TimezoneUpdateResponse(String timezone) {
    }
}
