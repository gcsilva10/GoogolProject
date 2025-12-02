package com.example.googolweb;

import common.GatewayInterface;
import common.SearchResult;
import common.StatisticsCallback;
import org.springframework.beans.factory.annotation.Autowired;
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

@Service
public class GoogolService {

    private GatewayInterface gateway;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private final String RMI_HOST = "localhost";
    private final int RMI_PORT = 1099;
    private final String GATEWAY_NAME = "GoogolGateway";

    @PostConstruct
    public void initConnection() {
        try {
            System.out.println(">>> [GoogolService] A tentar ligar à Gateway RMI...");
            Registry registry = LocateRegistry.getRegistry(RMI_HOST, RMI_PORT);
            this.gateway = (GatewayInterface) registry.lookup(GATEWAY_NAME);
            
            registerStatsCallback();
            
            System.out.println(">>> [GoogolService] SUCESSO: Ligado à Gateway!");
        } catch (RemoteException | NotBoundException e) {
            System.err.println(">>> [GoogolService] AVISO: Não foi possível ligar à Gateway (" + e.getMessage() + ")");
        }
    }

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

    public List<SearchResult> search(String query) {
        if (gateway == null) return Collections.emptyList();
        try {
            return gateway.search(query);
        } catch (RemoteException e) {
            // Tenta reconectar silenciosamente
            initConnection(); 
            return Collections.emptyList();
        }
    }

    public boolean indexURL(String url) {
        if (gateway == null) return false;
        try {
            gateway.indexNewURL(url);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    // NOVO: Obter Backlinks
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
    public class StatisticsCallbackImpl extends UnicastRemoteObject implements StatisticsCallback {
        protected StatisticsCallbackImpl() throws RemoteException { super(); }

        @Override
        public void onStatisticsUpdate(String statistics) throws RemoteException {
            messagingTemplate.convertAndSend("/topic/stats", statistics);
        }
    }
}