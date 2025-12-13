package com.example.googolweb;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Classe de configuração para WebSockets e protocolo STOMP.
 * <p>
 * Habilita o "Message Broker" que permite a comunicação bidirecional em tempo real
 * entre o servidor (Spring Boot) e o cliente (Browser/JavaScript).
 * É utilizado para enviar as atualizações das estatísticas do sistema sem necessidade de "refresh".
 * </p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Configura o broker de mensagens.
     * Define os prefixos para os canais de comunicação.
     * * @param config O registo do broker a configurar.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Habilita um broker de memória simples para enviar mensagens aos clientes
        // O cliente subscreve em canais que começam por "/topic" (ex: /topic/stats)
        config.enableSimpleBroker("/topic");
        
        // Prefixo para mensagens enviadas do cliente para o servidor (não usado intensivamente neste projeto)
        config.setApplicationDestinationPrefixes("/app");
    }

    /**
     * Regista os "endpoints" STOMP, que são os pontos de entrada para o WebSocket.
     * * @param registry O registo de endpoints.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Regista o endpoint "/googol-websocket" permitindo ligações de qualquer origem.
        // Habilita SockJS como fallback para browsers que não suportem WebSocket nativo.
        registry.addEndpoint("/googol-websocket").withSockJS();
    }
}