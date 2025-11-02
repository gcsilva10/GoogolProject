package gateway;

import common.BarrelInterface;
import common.GatewayInterface;
import common.SearchResult;
import common.StatisticsCallback;
import common.Config;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementação da Gateway RPC/RMI - ponto central de coordenação do sistema Googol.
 * 
 * <p>Responsabilidades principais:</p>
 * <ul>
 *   <li>Gerir fila de URLs para indexação (produtor-consumidor com Downloaders)</li>
 *   <li>Distribuir pesquisas pelos Barrels usando round-robin com failover</li>
 *   <li>Agregar estatísticas do sistema e notificar Clientes via callbacks</li>
 *   <li>Registar URLs indexados em ficheiro de log</li>
 *   <li>Implementar balanceamento de carga e tolerância a falhas</li>
 * </ul>
 * 
 * <p>Thread-safety: Utiliza estruturas concorrentes (ConcurrentHashMap, CopyOnWriteArrayList)
 * e sincronização explícita onde necessário.</p>
 */
public class Gateway extends UnicastRemoteObject implements GatewayInterface {

    private static final String LOG_FILE = "indexed_urls.log";
    private final Object logLock = new Object();

    private final Queue<String> urlQueue;
    private final Set<String> visitedURLs;
    private final List<BarrelInterface> barrels;
    private final List<String> barrelNames;
    private final AtomicInteger nextBarrelIndex;

    private final Map<String, AtomicInteger> topSearches;
    private final Map<String, Long> barrelResponseTimes;
    private final Map<String, AtomicInteger> barrelSearchCounts;
    
    private final List<StatisticsCallback> statisticsCallbacks;
    private String lastStatistics = "";

    /**
     * Cria uma nova instância da Gateway e inicializa todos os componentes.
     * 
     * @param barrelNames Lista de nomes RMI dos Barrels disponíveis
     * @throws RemoteException Se houver falha ao exportar o objeto RMI
     */
    protected Gateway(List<String> barrelNames) throws RemoteException {
        super();
        this.urlQueue = new ConcurrentLinkedQueue<>();
        this.barrels = new CopyOnWriteArrayList<>(); // Thread-safe para leituras
        this.barrelNames = barrelNames;
        this.nextBarrelIndex = new AtomicInteger(0);
        
        this.topSearches = new ConcurrentHashMap<>();
        this.barrelResponseTimes = new ConcurrentHashMap<>();
        this.barrelSearchCounts = new ConcurrentHashMap<>();
        
        this.visitedURLs = ConcurrentHashMap.newKeySet();
        
        this.statisticsCallbacks = new CopyOnWriteArrayList<>();
        
        connectToBarrels();
        
        startStatisticsMonitoringThread();
    }

    /**
     * Conecta (ou reconecta) a todos os Barrels configurados via RMI Registry.
     * Barrels que não estão acessíveis são ignorados mas podem ser reconectados depois.
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
     * Obtém o próximo Barrel a usar para pesquisa usando algoritmo round-robin.
     * Garante distribuição uniforme de carga entre Barrels ativos.
     * 
     * @return Referência RMI para um Barrel, ou null se nenhum estiver disponível
     */
    private BarrelInterface getNextBarrel() {
        if (barrels.isEmpty()) {
            return null;
        }
        // Usa Math.abs para garantir índice positivo caso o AtomicInteger dê a volta
        int index = Math.abs(nextBarrelIndex.getAndIncrement()) % barrels.size();
        return barrels.get(index);
    }
    
    /**
     * {@inheritDoc}
     * 
     * <p>Implementa deduplicação de URLs (ignora duplicados) e persiste
     * todos os URLs indexados em ficheiro de log.</p>
     */
    @Override
    public void indexNewURL(String url) throws RemoteException {
        if (visitedURLs.add(url)) {
            System.out.println("[Gateway] Novo URL recebido para indexar: " + url);
            urlQueue.offer(url);
            
            synchronized (logLock) {
                try (FileWriter fw = new FileWriter(LOG_FILE, true);
                     BufferedWriter bw = new BufferedWriter(fw);
                     PrintWriter out = new PrintWriter(bw)) 
                {
                    out.println(url);
                } catch (IOException e) {
                    System.err.println("[Gateway] Erro ao escrever no ficheiro de log: " + e.getMessage());
                }
            }
            
            notifyStatisticsChange();
        }
    }

    /**
     * {@inheritDoc}
     * 
     * <p>Implementa:</p>
     * <ul>
     *   <li>Round-robin para balanceamento de carga</li>
     *   <li>Failover automático se um Barrel falhar</li>
     *   <li>Atualização de estatísticas (top searches, tempos de resposta)</li>
     *   <li>Ordenação final por relevância (backlinks)</li>
     * </ul>
     */
    @Override
    public List<SearchResult> search(String query) throws RemoteException {
        System.out.println("[Gateway] Nova pesquisa recebida: " + query);
        
        topSearches.computeIfAbsent(query.toLowerCase(), k -> new AtomicInteger(0)).incrementAndGet();
        
        notifyStatisticsChange();

        String[] terms = query.toLowerCase().split("\\s+");
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

            long startTime = System.nanoTime();
            try {
                List<SearchResult> results = barrel.search(terms);
                
                long duration = (System.nanoTime() - startTime) / 100000;
                
                String barrelName = "BarrelDesconhecido";
                try {
                    barrelName = barrelNames.get(i % barrelNames.size());
                } catch (Exception e) { }
                
                barrelResponseTimes.merge(barrelName, duration, Long::sum);
                barrelSearchCounts.computeIfAbsent(barrelName, k -> new AtomicInteger(0)).incrementAndGet();

                results.sort((r1, r2) -> Integer.compare(r2.getRelevance(), r1.getRelevance()));

                return results;
            } catch (RemoteException e) {
                System.err.println("[Gateway] Falha ao contactar Barrel. A tentar o próximo...");
                barrels.remove(barrel);
            }
        }
        
        throw new RemoteException("Falha ao contactar todos os Storage Barrels disponíveis.");
    }

    /**
     * {@inheritDoc}
     * 
     * <p>Implementa failover automático tentando múltiplos Barrels se necessário.</p>
     */
    @Override
    public List<String> getBacklinks(String url) throws RemoteException {
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
                return barrel.getBacklinks(url);
            } catch (RemoteException e) {
                System.err.println("[Gateway] Falha ao obter backlinks. A tentar o próximo...");
                barrels.remove(barrel);
            }
        }
        
        throw new RemoteException("Falha ao contactar todos os Storage Barrels para obter backlinks.");
    }

        
    /**
     * {@inheritDoc}
     */
    @Override
    public String getStatistics() throws RemoteException {
        return buildStatistics();
    }

    /**
     * {@inheritDoc}
     * 
     * <p>Implementa o padrão produtor-consumidor: remove e retorna um URL da fila.</p>
     */
    @Override
    public String getURLToCrawl() throws RemoteException {
        return urlQueue.poll();
    }
    
    /**
     * {@inheritDoc}
     * 
     * <p>Envia estatísticas iniciais imediatamente após registo bem-sucedido.</p>
     */
    @Override
    public void registerStatisticsCallback(StatisticsCallback callback) throws RemoteException {
        if (!statisticsCallbacks.contains(callback)) {
            statisticsCallbacks.add(callback);
            System.out.println("[Gateway] Cliente registado para receber atualizações de estatísticas.");
            
            try {
                callback.onStatisticsUpdate(getStatistics());
            } catch (RemoteException e) {
                System.err.println("[Gateway] Falha ao enviar estatísticas iniciais ao cliente.");
                statisticsCallbacks.remove(callback);
            }
        }
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void unregisterStatisticsCallback(StatisticsCallback callback) throws RemoteException {
        statisticsCallbacks.remove(callback);
        System.out.println("[Gateway] Cliente desregistado de atualizações de estatísticas.");
    }
    
    /**
     * Notifica todos os callbacks registados com as novas estatísticas.
     * Remove automaticamente callbacks que falharem (Clientes desconectados).
     * 
     * @param statistics String formatada com as estatísticas
     */
    private void notifyStatisticsCallbacks(String statistics) {
        List<StatisticsCallback> failedCallbacks = new ArrayList<>();
        
        for (StatisticsCallback callback : statisticsCallbacks) {
            try {
                callback.onStatisticsUpdate(statistics);
            } catch (RemoteException e) {
                System.err.println("[Gateway] Falha ao notificar cliente: " + e.getMessage());
                failedCallbacks.add(callback);
            }
        }
        
        for (StatisticsCallback failed : failedCallbacks) {
            statisticsCallbacks.remove(failed);
            System.out.println("[Gateway] Cliente removido automaticamente (desconectado).");
        }
    }
    
    /**
     * Verifica se as estatísticas mudaram e notifica callbacks apenas se houver mudanças.
     * Otimização para evitar notificações desnecessárias.
     */
    private void notifyStatisticsChange() {
        if (!statisticsCallbacks.isEmpty()) {
            try {
                String newStats = buildStatistics();
                if (!newStats.equals(lastStatistics)) {
                    System.out.println("[Gateway] Estatísticas mudaram, a notificar " + statisticsCallbacks.size() + " cliente(s)...");
                    lastStatistics = newStats;
                    notifyStatisticsCallbacks(newStats);
                }
            } catch (Exception e) {
                System.err.println("[Gateway] Erro ao notificar mudança de estatísticas: " + e.getMessage());
            }
        }
    }
    
    /**
     * Constrói a string formatada com todas as estatísticas do sistema.
     * Inclui top 10 pesquisas, Barrels ativos e tempos médios de resposta.
     * 
     * @return String formatada com estatísticas completas
     * @throws RemoteException Se houver falha ao comunicar com os Barrels
     */
    private String buildStatistics() throws RemoteException {
        StringBuilder stats = new StringBuilder();
        stats.append("== Estatísticas do Googol ==\n");

        stats.append("\n-- 10 Pesquisas Mais Comuns --\n");
        topSearches.entrySet().stream()
            .sorted((e1, e2) -> Integer.compare(e2.getValue().get(), e1.getValue().get()))
            .limit(10)
            .forEach(entry -> stats.append(String.format("'%s': %d pesquisas\n", entry.getKey(), entry.getValue().get())));

        stats.append("\n-- Barrels Ativos --\n");
        for (int i=0; i < barrels.size(); i++) {
            BarrelInterface barrel = barrels.get(i);
            String barrelName = "BarrelDesconhecido";
            try {
                 barrelName = barrelNames.get(i % barrelNames.size());
            } catch (Exception e) { }

            try {
                stats.append(String.format("[%s] %s\n", barrelName, barrel.getBarrelStats()));
            } catch (RemoteException e) {
                stats.append(String.format("[%s] Inacessível.\n", barrelName));
                barrels.remove(barrel);
            }
        }
        
        stats.append("\n-- Tempo Médio de Resposta (décimas de segundo) --\n");
        barrelSearchCounts.forEach((name, count) -> {
            long totalTime = barrelResponseTimes.getOrDefault(name, 0L);
            long avgTime = (count.get() == 0) ? 0 : totalTime / count.get();
            stats.append(String.format("[%s] Média: %d (total: %d, pesquisas: %d)\n", name, avgTime, totalTime, count.get()));
        });

        return stats.toString();
    }
    
    /**
     * Inicia thread daemon que verifica periodicamente mudanças nas estatísticas.
     * Necessário para detectar mudanças nos Barrels (novos URLs indexados)
     * que não são causadas diretamente por ações na Gateway.
     */
    private void startStatisticsMonitoringThread() {
        Thread monitorThread = new Thread(() -> {
            long monitorInterval = Config.getStatisticsMonitorInterval();
            System.out.println("[Gateway] Thread de monitorização de estatísticas iniciada (intervalo: " + monitorInterval + "ms).");
            
            while (true) {
                try {
                    Thread.sleep(monitorInterval);
                    
                    if (!statisticsCallbacks.isEmpty()) {
                        notifyStatisticsChange();
                    }
                    
                } catch (InterruptedException e) {
                    System.out.println("[Gateway] Thread de monitorização interrompida.");
                    break;
                } catch (Exception e) {
                    System.err.println("[Gateway] Erro na thread de monitorização: " + e.getMessage());
                }
            }
        });
        
        monitorThread.setDaemon(true);
        monitorThread.setName("StatisticsMonitor");
        monitorThread.start();
    }

    /**
     * Método principal para iniciar a Gateway.
     * Configura RMI, conecta ao Registry e exporta a Gateway como serviço RMI.
     * 
     * @param args Argumentos de linha de comando (não utilizados)
     */
    public static void main(String[] args) {
        System.setProperty("java.security.policy", "security.policy");
        System.setProperty("java.rmi.server.hostname", "localhost");

        try {
            // Lê configurações do ficheiro config.properties
            String gatewayName = Config.getGatewayName();
            List<String> barrelNames = Config.getBarrelsList();
            String rmiHost = Config.getRmiHost();
            int rmiPort = Config.getRmiPort();
            
            System.out.println("[Gateway] A iniciar com nome: " + gatewayName);
            System.out.println("[Gateway] Barrels configurados: " + barrelNames);
            System.out.println("[Gateway] RMI Registry: " + rmiHost + ":" + rmiPort);
            
            Gateway gateway = new Gateway(barrelNames);
            
            Registry registry;
            try {
                registry = LocateRegistry.getRegistry(rmiHost, rmiPort);
                registry.list();
                System.out.println("[Gateway] Conectado ao RMI Registry existente.");
            } catch (Exception e) {
                System.out.println("[Gateway] RMI Registry não encontrado. A criar novo registry...");
                registry = LocateRegistry.createRegistry(rmiPort);
                System.out.println("[Gateway] RMI Registry criado na porta " + rmiPort);
            }
            
            registry.rebind(gatewayName, gateway);

            System.out.println("[Gateway] pronta e ligada ao RMI Registry como '" + gatewayName + "'.");
            System.out.println("[Gateway] Aguardando conexões de clientes...");
            
            Object keepAlive = new Object();
            synchronized (keepAlive) {
                keepAlive.wait();
            }
            
        } catch (InterruptedException e) {
            System.out.println("[Gateway] Interrompida.");
        } catch (Exception e) {
            System.err.println("[Gateway] exceção: " + e.toString());
            e.printStackTrace();
        }
    }
}