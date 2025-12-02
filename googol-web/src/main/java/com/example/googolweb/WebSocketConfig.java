package com.example.googolweb;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Define o prefixo para os tópicos onde o cliente subscreve (ex: /topic/stats)
        config.enableSimpleBroker("/topic");
        // Prefixo para mensagens que vêm do cliente (não vamos usar muito aqui, mas é padrão)
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // O ponto de entrada onde o JavaScript se vai ligar
        registry.addEndpoint("/googol-websocket").withSockJS();
    }
}