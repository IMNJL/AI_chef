package com.aichef.controller;

import com.aichef.service.IcsFeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/ical")
public class IcsController {

    private final IcsFeedService icsFeedService;

    @GetMapping("/{token}")
    public ResponseEntity<String> feed(@PathVariable("token") String token) {
        return icsFeedService.buildIcsByToken(token)
                .map(ics -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, "text/calendar; charset=utf-8")
                        .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                        .header(HttpHeaders.PRAGMA, "no-cache")
                        .header(HttpHeaders.EXPIRES, "0")
                        .body(ics))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
