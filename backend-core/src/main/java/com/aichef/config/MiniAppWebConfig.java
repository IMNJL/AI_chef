package com.aichef.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;

@Configuration
public class MiniAppWebConfig implements WebMvcConfigurer {

    @Value("${app.miniapp.public-url:}")
    private String miniappPublicUrl;

    @Value("${app.miniapp.allow-insecure:true}")
    private boolean allowInsecure;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String origin = resolveOrigin(miniappPublicUrl);
        if (origin == null && !allowInsecure) {
            return;
        }
        registry.addMapping("/api/miniapp/**")
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedOrigins(origin == null ? "*" : origin)
                .allowCredentials(false);
    }

    private String resolveOrigin(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) {
                return null;
            }
            StringBuilder origin = new StringBuilder(scheme).append("://").append(host);
            if (port > 0) {
                origin.append(":").append(port);
            }
            return origin.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
