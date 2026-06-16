package com.insightspark.core.auth;

/**
 * Maps API paths to RBAC permission codes. Unmapped admin endpoints still require ADMIN role.
 */
public final class RbacEndpointGuard {

    private RbacEndpointGuard() {
    }

    public static String requiredPermission(String uri, String method) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        if (uri.startsWith("/api/admin/user-permission")) {
            return "operation:rbac-manage";
        }
        if (uri.startsWith("/api/datasource") || uri.startsWith("/api/datasources")) {
            return "menu:datasource-admin";
        }
        if (uri.startsWith("/api/audit")) {
            return "menu:sql-audit";
        }
        if (uri.startsWith("/api/permission/admin")) {
            return "menu:permission-approval";
        }
        if (uri.startsWith("/api/chat")) {
            return "menu:chat-analysis";
        }
        if (uri.startsWith("/api/diagnosis")) {
            return "menu:diagnosis";
        }
        if (uri.startsWith("/api/permission") && !uri.startsWith("/api/permission/admin")) {
            return "menu:permission-center";
        }
        if (uri.startsWith("/api/data") && isWriteMethod(method)) {
            return "menu:data-upload";
        }
        return null;
    }

    public static boolean isLegacyAdminEndpoint(String uri) {
        if (uri == null || uri.isBlank()) {
            return false;
        }
        return uri.startsWith("/api/admin")
                || uri.startsWith("/api/knowledge-graph")
                || uri.startsWith("/api/c/admin");
    }

    private static boolean isWriteMethod(String method) {
        if (method == null) {
            return false;
        }
        String m = method.trim().toUpperCase();
        return "POST".equals(m) || "PUT".equals(m) || "PATCH".equals(m) || "DELETE".equals(m);
    }
}
