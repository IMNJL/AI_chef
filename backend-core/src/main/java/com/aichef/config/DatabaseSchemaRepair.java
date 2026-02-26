package com.aichef.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseSchemaRepair {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureColumns() {
        try {
            jdbcTemplate.execute("ALTER TABLE meetings ADD COLUMN IF NOT EXISTS color VARCHAR(7)");
            log.info("Schema repair check complete: meetings.color");
        } catch (Exception e) {
            log.warn("Schema repair failed for meetings.color: {}", e.getMessage());
        }
    }
}

