package com.aichef.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "google_oauth_states")
public class GoogleOAuthState {

    @Id
    @Column(nullable = false, length = 120)
    private String state;

    @Column(name = "telegram_id", nullable = false)
    private Long telegramId;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;
}
