package com.insightspark.core.auth;

public final class AuthContext {

    private static final ThreadLocal<UserPrincipal> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(UserPrincipal principal) {
        CURRENT.set(principal);
    }

    public static UserPrincipal get() {
        UserPrincipal principal = CURRENT.get();
        if (principal == null) {
            throw new IllegalStateException("Unauthorized");
        }
        return principal;
    }

    public static String userId() {
        return get().userId();
    }

    public static String role() {
        return get().role();
    }

    public static boolean isAdmin() {
        String r = role();
        return "ADMIN".equalsIgnoreCase(r) || isSuperAdmin();
    }

    public static boolean isSuperAdmin() {
        return RbacConstants.SUPER_ADMIN_ROLE.equalsIgnoreCase(role());
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record UserPrincipal(Long id, String userId, String username, String nickname, String role) {
    }
}
