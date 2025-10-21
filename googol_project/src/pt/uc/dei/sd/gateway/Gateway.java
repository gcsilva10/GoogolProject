package pt.uc.dei.sd.gateway;

import pt.uc.dei.sd.common.BarrelInterface;
import pt.uc.dei.sd.common.GatewayInterface;
import pt.uc.dei.sd.common.SearchResult;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
// import java.util.Collections; // Não é necessário com List.sort
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set; // Importado
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementação da Gateway RPC/RMI.
 * Ponto de entrada para Clientes. Gere a fila de URLs.
 * Comunica com os Barrels.
 */
public class Gateway extends UnicastRemoteObject implements GatewayInterface {

    private final Queue<String> urlQueue; // Fila de URLs para os Downloaders
    private final Set<String> visitedURLs; // Set de URLs já visitados/em-fila
    private final List<BarrelInterface> barrels; // Lista de stubs dos Barrels ativos
    private final List<String> barrelNames; // Nomes RMI dos Barrels (para reconexão)
    private final AtomicInteger nextBarrelIndex; // Para Round-Robin

    // Para estatísticas
    private final Map<String, AtomicInteger> topSearches;
    private final Map<String, Long> barrelResponseTimes; // BarrelName -> Tempo total
    private final Map<String, AtomicInteger> barrelSearchCounts; // BarrelName -> Contagem

    protected Gateway(List<String> barrelNames) throws RemoteException {
        super();
        this.urlQueue = new ConcurrentLinkedQueue<>();
        this.barrels = new CopyOnWriteArrayList<>(); // Thread-safe para leituras
        this.barrelNames = barrelNames;
        this.nextBarrelIndex = new AtomicInteger(0);
        
        this.topSearches = new ConcurrentHashMap<>();
        this.barrelResponseTimes = new ConcurrentHashMap<>();
        this.barrelSearchCounts = new ConcurrentHashMap<>();
        
        // Usa um Set thread-safe para rastrear URLs visitados
        this.visitedURLs = ConcurrentHashMap.newKeySet();
        
        connectToBarrels();
    }

    /**
     * Tenta ligar-se (ou religar-se) a todos os Barrels da lista.
     */
    private void connectToBarrels() {
        barrels.clear();
        Registry registry;
        try {
            registry = LocateRegistry.getRegistry();
            for (String name : barrelNames) {
                try {
                    BarrelInterface barrel = (BarrelInterface) registry.lookup(name);
                    barrels.add(barrel);
                    System.out.println("[Gateway] Ligado com sucesso a " + name);
                } catch (Exception e) {
                    System.err.println("[Gateway] Falha ao ligar a " + name + ": " + e.getMessage());
                }
            }
        } catch (RemoteException e1) {
             System.err.println("[Gateway] Falha ao obter RMI registry: " + e1.getMessage());
        }
    }

    /**
     * Obtém o próximo Barrel a usar, implementando Round-Robin.
     */
    private BarrelInterface getNextBarrel() {
        if (barrels.isEmpty()) {
            return null;
        }
        // Usa Math.abs para garantir índice positivo caso o AtomicInteger dê a volta
        int index = Math.abs(nextBarrelIndex.getAndIncrement()) % barrels.size();
        return barrels.get(index);
    }
    
    @Override
    public void indexNewURL(String url) throws RemoteException {
        // Lógica alterada para impedir duplicados
        // O método .add() de um Set (como o newKeySet)
        // é atómico e retorna 'true' se o elemento foi
        // adicionado, ou 'false' se já existia.
        if (visitedURLs.add(url)) {
            // Só adiciona à fila se for um URL novo
            System.out.println("[Gateway] Novo URL recebido para indexar: " + url);
            urlQueue.offer(url); // Adiciona URL à fila
        }
        // Se o URL já existir no 'visitedURLs', é simplesmente ignorado.
    }

    @Override
    public List<SearchResult> search(String query) throws RemoteException {
        System.out.println("[Gateway] Nova pesquisa recebida: " + query);
        
        // Atualizar estatísticas de pesquisa
        topSearches.computeIfAbsent(query.toLowerCase(), k -> new AtomicInteger(0)).incrementAndGet();

        String[] terms = query.toLowerCase().split("\\s+");
        int maxRetries = barrels.size();
        
        for (int i = 0; i < maxRetries; i++) {
            BarrelInterface barrel = getNextBarrel();
            if (barrel == null) {
                connectToBarrels(); // Tenta reconectar se não houver barrels
                if (barrels.isEmpty()) {
                    throw new RemoteException("Nenhum Storage Barrel está disponível.");
                }
                barrel = getNextBarrel(); // Tenta obter novamente
                if (barrel == null) { // Se ainda for nulo, algo está muito errado
                     throw new RemoteException("Nenhum Storage Barrel disponível após reconexão.");
                }
            }

            long startTime = System.nanoTime();
            try {
                // 1. Fazer o pedido ao Barrel
                List<SearchResult> results = barrel.search(terms);
                
                // 2. Atualizar estatísticas de tempo de resposta
                long duration = (System.nanoTime() - startTime) / 100000; // Décimas de segundo
                
                // Tenta encontrar o nome do barrel de forma segura
                String barrelName = "BarrelDesconhecido";
                try {
                    barrelName = barrelNames.get(i % barrelNames.size()); // Aproximação do nome
                } catch (Exception e) { /* ignora */ }
                
                barrelResponseTimes.merge(barrelName, duration, Long::sum);
                barrelSearchCounts.computeIfAbsent(barrelName, k -> new AtomicInteger(0)).incrementAndGet();

                // 3. Re-ordenar os resultados por relevância (número de backlinks)
                results.sort((r1, r2) -> Integer.compare(r2.getRelevance(), r1.getRelevance()));

                // 4. Retorna a lista completa. O Cliente trata da paginação 10 a 10.
                return results;

            } catch (RemoteException e) {
                // Implementação de Failover
                System.err.println("[Gateway] Falha ao contactar Barrel. A tentar o próximo...");
                barrels.remove(barrel); // Remove o barrel que falhou da lista ativa
            }
        }
        
        throw new RemoteException("Falha ao contactar todos os Storage Barrels disponíveis.");
    }

    @Override
    public List<String> getBacklinks(String url) throws RemoteException {
        // Implementa failover, tal como a pesquisa
        int maxRetries = barrels.size();

        for (int i = 0; i < maxRetries; i++) {
            BarrelInterface barrel = getNextBarrel();
             if (barrel == null) {
                connectToBarrels();
                if (barrels.isEmpty()) {
                    throw new RemoteException("Nenhum Storage Barrel está disponível.");
                }
                barrel = getNextBarrel();
                 if (barrel == null) {
                     throw new RemoteException("Nenhum Storage Barrel disponível após reconexão.");
                }
            }

            try {
                // Se funcionar, retorna imediatamente
                return barrel.getBacklinks(url);
            } catch (RemoteException e) {
                // Failover
                System.err.println("[Gateway] Falha ao obter backlinks. A tentar o próximo...");
                barrels.remove(barrel); // Remove o barrel que falhou
            }
        }
        
        throw new RemoteException("Falha ao contactar todos os Storage Barrels para obter backlinks.");
    }

    @Override
    public String getStatistics() throws RemoteException {
        // Implementar agregação de estatísticas
        StringBuilder stats = new StringBuilder();
        stats.append("== Estatísticas do Googol ==\n");

        // 1. 10 pesquisas mais comuns
        stats.append("\n-- 10 Pesquisas Mais Comuns --\n");
        // Lógica para ordenar `topSearches` e obter o top 10
        topSearches.entrySet().stream()
            .sorted((e1, e2) -> Integer.compare(e2.getValue().get(), e1.getValue().get()))
            .limit(10)
            .forEach(entry -> stats.append(String.format("'%s': %d pesquisas\n", entry.getKey(), entry.getValue().get())));


        // 2. Barrels ativos e seus tamanhos
        stats.append("\n-- Barrels Ativos --\n");
        for (int i=0; i < barrels.size(); i++) {
            BarrelInterface barrel = barrels.get(i);
            String barrelName = "BarrelDesconhecido";
            try {
                 barrelName = barrelNames.get(i % barrelNames.size()); // Tenta obter nome
            } catch (Exception e) {/* ignora */}

            try {
                stats.append(String.format("[%s] %s\n", barrelName, barrel.getBarrelStats()));
            } catch (RemoteException e) {
                stats.append(String.format("[%s] Inacessível.\n", barrelName));
                barrels.remove(barrel); // Remove se falhou aqui
            }
        }
        
        // 3. Tempo médio de resposta por Barrel
        stats.append("\n-- Tempo Médio de Resposta (décimas de segundo) --\n");
        // Lógica para calcular média: barrelResponseTimes / barrelSearchCounts
         barrelSearchCounts.forEach((name, count) -> {
            long totalTime = barrelResponseTimes.getOrDefault(name, 0L);
            long avgTime = (count.get() == 0) ? 0 : totalTime / count.get();
            stats.append(String.format("[%s] Média: %d (total: %d, pesquisas: %d)\n", name, avgTime, totalTime, count.get()));
        });

        return stats.toString();
    }

    @Override
    public String getURLToCrawl() throws RemoteException {
        return urlQueue.poll(); // Retira e retorna o URL da fila
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Uso: java pt.uc.dei.sd.gateway.Gateway <rmi-barrel-name-1> <rmi-barrel-name-2> ...");
            System.err.println("Exemplo: java pt.uc.dei.sd.gateway.Gateway GoogolBarrel1 GoogolBarrel2");
            System.exit(1);
        }
        
        List<String> barrelNames = List.of(args);
        
        System.setProperty("java.security.policy", "security.policy");
        // System.setSecurityManager(new SecurityManager());

        try {
            Gateway gateway = new Gateway(barrelNames);
            Registry registry = LocateRegistry.getRegistry();
            registry.rebind("GoogolGateway", gateway);

            System.out.println("[Gateway] pronta e ligada ao RMI Registry como 'GoogolGateway'.");
        } catch (Exception e) {
            System.err.println("[Gateway] exceção: " + e.toString());
            e.printStackTrace();
        }
    }
}