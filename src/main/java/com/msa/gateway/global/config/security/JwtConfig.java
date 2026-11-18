package com.msa.gateway.global.config.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Account와 같은 RS256·시간·issuer·audience·token_use 검증 계약을 적용합니다. */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtConfig {
    @Bean
    public ReactiveJwtDecoder jwtDecoder(AuthProperties properties) {
        // issuer discovery를 호출하지 않아 Account 부재에도 Gateway 자체는 시작합니다.
        var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(properties.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "잘못된 audience입니다.", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(properties.issuer()), audience,
                new JwtClaimValidator<Instant>("exp", Objects::nonNull),
                new JwtClaimValidator<String>("token_use", "access"::equals)));
        return decoder;
    }
}
