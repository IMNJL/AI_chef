package com.aichef.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class MiniAppWebConfig implements WebMvcConfigurer {

    @Value("${app.miniapp.public-url:}")
    private String miniappPublicUrl;

    @Value("${app.miniapp.allow-insecure:true}")
    private boolean allowInsecure;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String origin = resolveOrigin(miniappPublicUrl);
        if (allowInsecure) {
            List<String> patterns = new ArrayList<>();
            patterns.add("http://localhost:*");
            patterns.add("http://127.0.0.1:*");
            patterns.add("http://0.0.0.0:*");
            patterns.add("https://localhost:*");
            patterns.add("https://127.0.0.1:*");
            // patterns.add("https://*.ngrok-free.dev");
            // patterns.add("https://*.trycloudflare.com");
            patterns.add("https://*.github.io");
            if (origin != null) {
                patterns.add(origin);
            }

            var cors = registry.addMapping("/api/miniapp/**")
                    .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                    .allowedHeaders("*")
                    .allowedOriginPatterns(patterns.toArray(String[]::new));
            cors.allowCredentials(false);
            return;
        }

        if (origin == null) {
            return;
        }

        registry.addMapping("/api/miniapp/**")
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowedOrigins(origin)
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
