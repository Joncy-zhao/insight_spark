package com.insightspark.c.websocket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Objects;

@Component
public class CollabWebSocketHandler extends TextWebSocketHandler {

    private static final String ATTR_ROOM = "collabRoom";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private CollabWebSocketBroadcaster broadcaster;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> body = objectMapper.readValue(message.getPayload(), new TypeReference<>() {
        });
        String type = Objects.toString(body.get("type"), "").trim().toUpperCase();
        if ("JOIN".equals(type)) {
            handleJoin(session, body);
            return;
        }
        if ("PING".equals(type)) {
            session.sendMessage(new TextMessage("{\"type\":\"PONG\"}"));
        }
    }

    private void handleJoin(WebSocketSession session, Map<String, Object> body) throws Exception {
        String targetType = Objects.toString(body.get("targetType"), "").trim();
        long targetId = parseLong(body.get("targetId"));
        if (targetType.isBlank() || targetId <= 0) {
            return;
        }
        String prevRoom = (String) session.getAttributes().get(ATTR_ROOM);
        String roomKey = CollabWebSocketBroadcaster.roomKey(targetType, targetId);
        if (prevRoom != null && !prevRoom.equals(roomKey)) {
            broadcaster.leaveRoom(prevRoom, session);
        }
        session.getAttributes().put(ATTR_ROOM, roomKey);
        broadcaster.join(roomKey, session);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "JOINED",
                "payload", Map.of("targetType", targetType, "targetId", targetId)
        ))));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.leave(session);
    }

    private static long parseLong(Object v) {
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
