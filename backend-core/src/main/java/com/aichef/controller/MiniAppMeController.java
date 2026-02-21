package com.aichef.controller;

import com.aichef.domain.enums.Gender;
import com.aichef.domain.model.User;
import com.aichef.service.MiniAppAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/miniapp")
public class MiniAppMeController {

    private final MiniAppAuthService miniAppAuthService;

    @GetMapping("/me")
    public ResponseEntity<?> me(
            @RequestHeader(value = "X-Telegram-Init-Data", required = false) String initData,
            @RequestParam(value = "telegramId", required = false) Long telegramId
    ) {
        Optional<User> userOpt = miniAppAuthService.resolveUser(initData, telegramId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        User user = userOpt.get();
        Gender gender = user.getGender() == null ? Gender.UNKNOWN : user.getGender();
        return ResponseEntity.ok(new MeResponse(user.getId(), user.getTelegramId(), gender, toTitlePrefix(gender)));
    }

    private String toTitlePrefix(Gender gender) {
        if (gender == Gender.FEMALE) {
            return "Ms";
        }
        return "Mr";
    }

    public record MeResponse(UUID id, Long telegramId, Gender gender, String titlePrefix) {
    }
}
