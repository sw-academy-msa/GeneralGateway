package com.msa.gateway.global.config.monitoring;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 업무 인증과 별개인 읽기 전용 메트릭 토큰만 비교합니다. 토큰을 로그나 메트릭 라벨에 넣지 않습니다. */
public final class MonitoringAccess {
    private MonitoringAccess() {}

    /** 미설정, 공백, 너무 짧은 토큰과 GET 이외 요청은 닫힌 상태로 처리합니다. */
    public static boolean allowed(String expected, String header, String method) {
        if (!"GET".equals(method)
                || expected == null
                || expected.length() < 32
                || expected.isBlank()
                || header == null
                || !header.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                header.substring(7).getBytes(StandardCharsets.UTF_8));
    }
}
