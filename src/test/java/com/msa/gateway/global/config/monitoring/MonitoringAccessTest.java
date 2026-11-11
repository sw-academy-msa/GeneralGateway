package com.msa.gateway.global.config.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 기본 거부와 업무 JWT 대체 불가, 읽기 전용 수집 계약을 합성 값으로 검증합니다. */
class MonitoringAccessTest {
    private static final String TOKEN = "synthetic-monitoring-token-for-test-only";

    @Test
    void configuredTokenCanReadOnly() {
        assertThat(MonitoringAccess.allowed(TOKEN, "Bearer " + TOKEN, "GET")).isTrue();
        assertThat(MonitoringAccess.allowed(TOKEN, "Bearer " + TOKEN, "POST")).isFalse();
        assertThat(MonitoringAccess.allowed(TOKEN, "Bearer " + TOKEN, "DELETE")).isFalse();
    }

    @Test
    void missingOrInvalidCredentialCannotRead() {
        assertThat(MonitoringAccess.allowed("", "Bearer " + TOKEN, "GET")).isFalse();
        assertThat(MonitoringAccess.allowed("short", "Bearer short", "GET")).isFalse();
        assertThat(MonitoringAccess.allowed(TOKEN, null, "GET")).isFalse();
        assertThat(MonitoringAccess.allowed(TOKEN, "Bearer buyer-jwt", "GET")).isFalse();
        assertThat(MonitoringAccess.allowed(TOKEN, "Basic " + TOKEN, "GET")).isFalse();
    }
}
