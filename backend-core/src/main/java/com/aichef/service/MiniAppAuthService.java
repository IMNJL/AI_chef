package com.aichef.service;

import com.aichef.config.TelegramProperties;
import com.aichef.domain.model.User;
import com.aichef.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MiniAppAuthService {

    private final TelegramProperties telegramProperties;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.miniapp.allow-insecure:true}")
    private boolean allowInsecure;

    public Optional<User> resolveUser(String initData, Long telegramId) {
        Long idFromInitData = null;
        if (initData != null && !initData.isBlank()) {
            idFromInitData = parseTelegramIdFromInitData(initData);
            if (idFromInitData != null) {
                return Optional.of(getOrCreateUser(idFromInitData));
            }
        }

        if (allowInsecure && telegramId != null) {
            return Optional.of(getOrCreateUser(telegramId));
        }
        return Optional.empty();
    }

    public Long resolveTelegramId(String initData, Long telegramId) {
        if (initData != null && !initData.isBlank()) {
            Long id = parseTelegramIdFromInitData(initData);
            if (id != null) {
                return id;
            }
        }
        if (allowInsecure && telegramId != null) {
            return telegramId;
        }
        return null;
    }

    private Long parseTelegramIdFromInitData(String initData) {
        try {
            Map<String, String> data = parseQuery(initData);
            String hash = data.remove("hash");
            if (hash == null || hash.isBlank()) {
                return null;
            }
            String dataCheckString = buildDataCheckString(data);
            String computed = computeHash(dataCheckString);
            if (!computed.equalsIgnoreCase(hash)) {
                log.warn("MiniApp initData hash mismatch");
                return null;
            }
            String userJson = data.get("user");
            if (userJson == null || userJson.isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(userJson);
            JsonNode idNode = node.get("id");
            if (idNode == null || !idNode.isNumber()) {
                return null;
            }
            return idNode.asLong();
        } catch (Exception e) {
            log.warn("Failed to parse miniapp initData: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair.isBlank()) {
                continue;
            }
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            map.put(key, value);
        }
        return map;
    }

    private String buildDataCheckString(Map<String, String> data) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(data.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, String> entry = entries.get(i);
            sb.append(entry.getKey()).append("=").append(entry.getValue());
            if (i < entries.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private String computeHash(String dataCheckString) throws Exception {
        byte[] secretKey = hmacSha256("WebAppData", telegramProperties.botToken());
        byte[] result = hmacSha256(dataCheckString, secretKey);
        return toHex(result);
    }

    private byte[] hmacSha256(String data, String key) throws Exception {
        return hmacSha256(data, key.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] hmacSha256(String data, byte[] keyBytes) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private User getOrCreateUser(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .orElseGet(() -> {
                    User user = new User();
                    user.setTelegramId(telegramId);
                    return userRepository.save(user);
                });
    }
}
