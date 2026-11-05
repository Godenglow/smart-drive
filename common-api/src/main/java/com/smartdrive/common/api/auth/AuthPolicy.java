package com.smartdrive.common.api.auth;

public final class AuthPolicy {
    private AuthPolicy() {}

    /** Current API paths contain only literal segments and numeric IDs. Reject alternate
     * spellings before checking /api/: routing may decode escapes or remove matrix parameters. */
    public static boolean canonicalPath(String path) {
        if (path == null || !path.startsWith("/") || path.indexOf('%') >= 0
                || path.indexOf(';') >= 0 || path.indexOf('\\') >= 0 || path.contains("//")) return false;
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) return false;
        }
        return path.chars().noneMatch(character -> character <= 0x20 || character == 0x7f);
    }

    public static boolean publicPath(String path) {
        return path.equals("/api/auth/register") || path.equals("/api/auth/login")
                || path.equals("/actuator/health") || path.equals("/actuator/info")
                || path.startsWith("/actuator/account/") || path.startsWith("/actuator/driver/")
                || path.startsWith("/actuator/order/") || path.equals("/v3/api-docs")
                || path.startsWith("/v3/api-docs/") || path.startsWith("/swagger-ui")
                || path.startsWith("/webjars/");
    }

    public static boolean allowed(String path, String method, String role) {
        if (path.startsWith("/api/drivers/")) return "DRIVER".equals(role);
        if (path.startsWith("/api/orders")) {
            if (path.endsWith("/accept") || path.endsWith("/start") || path.endsWith("/finish"))
                return "DRIVER".equals(role);
            if (path.endsWith("/pay-demo") || path.endsWith("/cancel") ||
                    (path.equals("/api/orders") && "POST".equals(method)))
                return "PASSENGER".equals(role);
        }
        return true;
    }

    public static String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new IllegalArgumentException();
        String token = authorization.substring(7);
        if (token.isBlank() || !token.equals(token.trim())) throw new IllegalArgumentException();
        return token;
    }
}
