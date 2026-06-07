package com.insightspark.c.websocket;

import com.insightspark.core.auth.AuthContext;
import com.insightspark.service.AuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

@Component
public class CollabHandshakeInterceptor implements HandshakeInterceptor {

    @Autowired
    private AuthService authService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = extractToken(request.getURI());
        if (token == null || token.isBlank()) {
            return false;
        }
        AuthContext.UserPrincipal principal = authService.authenticate(token);
        if (principal == null) {
            return false;
        }
        attributes.put("userId", principal.userId());
        attributes.put("nickname", principal.nickname());
        attributes.put("role", principal.role());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private String extractToken(URI uri) {
        if (uri == null || uri.getQuery() == null) {
            return null;
        }
        for (String part : uri.getQuery().split("&")) {
            int idx = part.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            if ("token".equals(part.substring(0, idx))) {
                return part.substring(idx + 1);
            }
        }
        return null;
    }
}
