package com.insightspark.c.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CollabWebSocketBroadcaster {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Set<WebSocketSession>> rooms = new ConcurrentHashMap<>();

    public static String roomKey(String targetType, long targetId) {
        return targetType + ":" + targetId;
    }

    public void join(String roomKey, WebSocketSession session) {
        rooms.computeIfAbsent(roomKey, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void leave(WebSocketSession session) {
        rooms.values().forEach(set -> set.remove(session));
    }

    public void leaveRoom(String roomKey, WebSocketSession session) {
        Set<WebSocketSession> set = rooms.get(roomKey);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                rooms.remove(roomKey);
            }
        }
    }

    public void broadcast(String roomKey, String type, Object payload) {
        Set<WebSocketSession> set = rooms.get(roomKey);
        if (set == null || set.isEmpty()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(Map.of("type", type, "payload", payload));
            TextMessage message = new TextMessage(json);
            for (WebSocketSession session : Set.copyOf(set)) {
                if (session.isOpen()) {
                    session.sendMessage(message);
                } else {
                    set.remove(session);
                }
            }
        } catch (Exception ignored) {
            // best-effort broadcast
        }
    }

    public void broadcastCommentCreated(String targetType, long targetId, Map<String, Object> comment) {
        broadcast(roomKey(targetType, targetId), "COMMENT_CREATED", comment);
    }

    public void broadcastCommentDeleted(String targetType, long targetId, long commentId) {
        broadcast(roomKey(targetType, targetId), "COMMENT_DELETED", Map.of("id", commentId));
    }

    public void broadcastAnnotationCreated(long dashboardId, Map<String, Object> annotation) {
        broadcast(roomKey("DASHBOARD", dashboardId), "ANNOTATION_CREATED", annotation);
    }

    public void broadcastAnnotationDeleted(long dashboardId, long annotationId) {
        broadcast(roomKey("DASHBOARD", dashboardId), "ANNOTATION_DELETED", Map.of("id", annotationId));
    }
}
