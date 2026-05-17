package com.insightspark.core.auth;

import com.insightspark.service.AuthService;
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

        if (isAdminEndpoint(uri) && !"ADMIN".equalsIgnoreCase(principal.role())) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, "仅管理员可访问");
            return false;
        }

        AuthContext.set(principal);
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
