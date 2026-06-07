package com.insightspark.c.websocket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class CollabWebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private CollabWebSocketHandler collabWebSocketHandler;

    @Autowired
    private CollabHandshakeInterceptor collabHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(collabWebSocketHandler, "/ws/collab")
                .addInterceptors(collabHandshakeInterceptor)
                .setAllowedOrigins("http://localhost:5173", "http://127.0.0.1:5173");
    }
}
