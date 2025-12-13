package com.example.googolweb;

import common.GatewayInterface;
import common.SearchResult;
import common.StatisticsCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Collections;
import java.util.List;

/**
 * Serviço responsável pela comunicação entre o Servidor Web (Spring Boot) e o Backend (Gateway RMI).
 * <p>
 * Atua como um Cliente RMI que:
 * <ul>
 * <li>Estabelece e mantém a conexão com a Gateway.</li>
 * <li>Encaminha os pedidos de pesquisa, indexação e backlinks.</li>
 * <li>Gere o sistema de callbacks para estatísticas em tempo real.</li>
 * <li>Reencaminha atualizações da Gateway para os clientes Web via WebSocket (STOMP).</li>
 * </ul>
 */
@Service
public class GoogolService {

    private GatewayInterface gateway;
    
    /**
     * Template do Spring para envio de mensagens WebSocket.
     * Usado para fazer "push" das estatísticas para o frontend.
     */
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // --- CONFIGURAÇÃO DINÂMICA ---
    
    /**
     * Host onde corre o RMI Registry (Máquina #1).
     * Injetado a partir do ficheiro de configuração ou argumento da JVM via script de arranque.
     */
    @Value("${googol.gateway.host:localhost}")
    private String rmiHost;

    /**
     * Porta do RMI Registry.
     */
    @Value("${googol.gateway.port:1099}")
    private int rmiPort;

    private final String GATEWAY_NAME = "GoogolGateway";

    /**
     * Inicializa a conexão RMI após a construção do serviço.
     * Tenta localizar o Registry e a Gateway, e regista o callback de estatísticas.
     * Se falhar, o serviço arranca na mesma mas com funcionalidades limitadas.
     */
    @PostConstruct
    public void initConnection() {
        try {
            System.out.println(">>> [GoogolService] A tentar ligar à Gateway em: " + rmiHost + ":" + rmiPort);
            
            Registry registry = LocateRegistry.getRegistry(rmiHost, rmiPort);
            this.gateway = (GatewayInterface) registry.lookup(GATEWAY_NAME);
            
            // Regista-se imediatamente para receber atualizações
            registerStatsCallback();
            
            System.out.println(">>> [GoogolService] SUCESSO: Ligado à Gateway!");
        } catch (RemoteException | NotBoundException e) {
            System.err.println(">>> [GoogolService] AVISO: Não foi possível ligar à Gateway (" + e.getMessage() + ")");
        }
    }

    /**
     * Cria e regista o objeto de callback na Gateway.
     * Isto permite que a Gateway chame este servidor de volta quando houver novos dados.
     */
    private void registerStatsCallback() {
        try {
            StatisticsCallback callback = new StatisticsCallbackImpl();
            gateway.registerStatisticsCallback(callback);
            System.out.println(">>> [GoogolService] Callback de estatísticas registado.");
        } catch (RemoteException e) {
            System.err.println(">>> Erro ao registar callback: " + e.getMessage());
        }
    }

    // --- Métodos RMI ---

    /**
     * Encaminha um pedido de pesquisa para a Gateway.
     * Implementa tolerância a falhas básica: se a conexão RMI falhar, tenta reconectar uma vez.
     * * @param query A string de pesquisa.
     * @return Lista de resultados ou lista vazia em caso de erro.
     */
    public List<SearchResult> search(String query) {
        if (gateway == null) return Collections.emptyList();
        try {
            return gateway.search(query);
        } catch (RemoteException e) {
            System.err.println(">>> [GoogolService] Conexão perdida durante pesquisa. A tentar reconectar...");
            initConnection(); // Tenta reconectar
            try {
                if (gateway != null) return gateway.search(query);
            } catch (RemoteException retryEx) {
                // Falhou segunda vez
            }
            return Collections.emptyList();
        }
    }

    /**
     * Envia um pedido de indexação de URL para a Gateway.
     * * @param url O URL a indexar.
     * @return true se o pedido foi enviado com sucesso, false caso contrário.
     */
    public boolean indexURL(String url) {
        if (gateway == null) return false;
        try {
            gateway.indexNewURL(url);
            return true;
        } catch (RemoteException e) {
            System.err.println(">>> [GoogolService] Erro ao indexar URL: " + e.getMessage());
            return false;
        }
    }

    /**
     * Obtém a lista de backlinks para um determinado URL.
     * * @param url O URL alvo.
     * @return Lista de URLs que apontam para o alvo.
     */
    public List<String> getBacklinks(String url) {
        if (gateway == null) return Collections.emptyList();
        try {
            return gateway.getBacklinks(url);
        } catch (RemoteException e) {
            System.err.println("Erro ao obter backlinks: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // --- Inner Class Callback ---

    /**
     * Implementação local da interface de callback remoto.
     * Esta classe é exportada como objeto UnicastRemoteObject para poder ser chamada pela Gateway.
     * Quando recebe uma atualização, reencaminha para o WebSocket.
     */
    public class StatisticsCallbackImpl extends UnicastRemoteObject implements StatisticsCallback {
        
        protected StatisticsCallbackImpl() throws RemoteException { 
            super(); 
        }

        /**
         * Método invocado remotamente pela Gateway.
         * Recebe a string de estatísticas e publica no tópico "/topic/stats".
         */
        @Override
        public void onStatisticsUpdate(String statistics) throws RemoteException {
            // Envia para todos os clientes WebSocket subscritos
            messagingTemplate.convertAndSend("/topic/stats", statistics);
        }
    }
}