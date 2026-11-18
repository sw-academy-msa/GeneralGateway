package com.msa.gateway.global.config.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.net.URI;
import java.util.List;

/** 외부 토큰 issuer와 내부 공개키 주소를 분리하며 개인키를 보유하지 않습니다. */
@Validated
@ConfigurationProperties("gateway.auth")
public record AuthProperties(@NotBlank String issuer, @NotBlank String audience,
                             @NotBlank String jwkSetUri, List<String> allowedOrigins) {
    public AuthProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        for (String origin : allowedOrigins) {
            URI uri = URI.create(origin);
            // credentials CORS에 패턴·경로가 섞이지 않도록 시작할 때 검증합니다.
            if (origin.contains("*") || uri.getHost() == null || uri.getUserInfo() != null
                    || !List.of("http", "https").contains(uri.getScheme())
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                    || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("정확한 Origin만 지정해야 합니다.");
        }
    }
}
