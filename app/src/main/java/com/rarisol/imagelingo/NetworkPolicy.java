package com.rarisol.imagelingo;

import java.net.URL;

/** Central validation for endpoints that receive images or credentials. */
final class NetworkPolicy {
    private NetworkPolicy() {}

    static URL requireHttps(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("API 엔드포인트가 비어 있습니다.");
        }
        try {
            URL url = new URL(value.trim());
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IllegalArgumentException("API 엔드포인트는 HTTPS만 사용할 수 있습니다.");
            }
            if (url.getHost() == null || url.getHost().trim().isEmpty()) {
                throw new IllegalArgumentException("API 엔드포인트 호스트가 없습니다.");
            }
            if (url.getUserInfo() != null || url.getRef() != null) {
                throw new IllegalArgumentException("API 엔드포인트에 사용자 정보나 fragment를 넣을 수 없습니다.");
            }
            return url;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("API 엔드포인트 형식이 올바르지 않습니다.", e);
        }
    }

    static boolean isOfficialOpenAiEndpoint(String value) {
        try {
            return "api.openai.com".equalsIgnoreCase(requireHttps(value).getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    static URL requireGoogleTokenEndpoint(String value) {
        URL url = requireHttps(value);
        if (!"oauth2.googleapis.com".equalsIgnoreCase(url.getHost())
                || !"/token".equals(url.getPath())
                || url.getQuery() != null) {
            throw new IllegalArgumentException("Vertex 서비스 계정 token_uri가 Google OAuth 엔드포인트가 아닙니다.");
        }
        return url;
    }
}
