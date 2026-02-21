package com.aichef.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.google.calendar")
public record GoogleCalendarProperties(
        boolean enabled,
        String calendarId,
        String clientId,
        String clientSecret,
        String refreshToken,
        String redirectPath,
        String scopes,
        String tokenUri,
        String apiBase
) {
    public boolean isOAuthConfigured() {
        return enabled
                && notBlank(clientId)
                && notBlank(clientSecret);
    }

    public boolean isGlobalCalendarConfigured() {
        return enabled
                && notBlank(calendarId)
                && notBlank(clientId)
                && notBlank(clientSecret)
                && notBlank(refreshToken);
    }

    public String safeTokenUri() {
        return notBlank(tokenUri) ? tokenUri : "https://oauth2.googleapis.com/token";
    }

    public String safeApiBase() {
        return notBlank(apiBase) ? apiBase : "https://www.googleapis.com/calendar/v3";
    }

    public String safeRedirectPath() {
        return notBlank(redirectPath) ? redirectPath : "/api/google/callback";
    }

    public String safeScopes() {
        return notBlank(scopes) ? scopes : "openid email profile https://www.googleapis.com/auth/calendar";
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
