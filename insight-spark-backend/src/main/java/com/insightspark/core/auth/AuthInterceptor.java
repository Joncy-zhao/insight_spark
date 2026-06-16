package com.insightspark.core.auth;

import com.insightspark.service.AuthService;
import com.insightspark.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private AuthService authService;

    @Autowired
    private PermissionService permissionService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Let browser CORS preflight pass through, otherwise frontend sees Network Error.
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String uri = request.getRequestURI();
        if (isPublicAuthEndpoint(uri)) {
            return true;
        }

        boolean loginRequired = uri.startsWith("/api/data")
            || uri.startsWith("/api/auth/me")
            || uri.startsWith("/api/chat")
                || uri.startsWith("/api/advanced-analysis")
                || uri.startsWith("/api/admin")
                || uri.startsWith("/api/permission")
                || uri.startsWith("/api/datasource")
                || uri.startsWith("/api/datasources")
                || uri.startsWith("/api/audit")
                || uri.startsWith("/api/diagnosis")
                || uri.startsWith("/api/knowledge")
                || uri.startsWith("/api/knowledge-graph")
                || uri.startsWith("/api/c");

        if (!loginRequired) {
            return true;
        }

        AuthContext.UserPrincipal principal = authService.authenticate(request.getHeader("Authorization"));
        if (principal == null) {
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "请先登录");
            return false;
        }

        if (permissionService.isSuperAdminUser(principal.userId(), principal.role())) {
            AuthContext.set(principal);
            return true;
        }

        if (isAdminEndpoint(uri) && !hasEndpointAccess(principal, uri, request.getMethod())) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, "无访问权限");
            return false;
        }

        String featurePermission = RbacEndpointGuard.requiredPermission(uri, request.getMethod());
        if (featurePermission != null
                && !RbacEndpointGuard.isLegacyAdminEndpoint(uri)
                && !permissionService.hasPermissionFor(principal.userId(), principal.role(), featurePermission)) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, "无访问权限");
            return false;
        }

        AuthContext.set(principal);
        return true;
    }

    private boolean hasEndpointAccess(AuthContext.UserPrincipal principal, String uri, String method) {
        String permission = RbacEndpointGuard.requiredPermission(uri, method);
        if (permission != null) {
            return permissionService.hasPermissionFor(principal.userId(), principal.role(), permission);
        }
        if (RbacEndpointGuard.isLegacyAdminEndpoint(uri)) {
            return "ADMIN".equalsIgnoreCase(principal.role());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }

    private boolean isPublicAuthEndpoint(String uri) {
        return uri.startsWith("/api/auth/login")
                || uri.startsWith("/api/auth/register")
                || uri.startsWith("/api/auth/captcha")
                || uri.startsWith("/api/c/dashboards/share");
    }

    private boolean isAdminEndpoint(String uri) {
        return uri.startsWith("/api/datasource")
                || uri.startsWith("/api/datasources")
                || uri.startsWith("/api/audit")
                || uri.startsWith("/api/admin")
                || uri.startsWith("/api/knowledge-graph")
                || uri.startsWith("/api/permission/admin")
                || uri.startsWith("/api/c/admin");
    }

    private void writeJson(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
