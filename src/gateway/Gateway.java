package gateway;

import common.BarrelInterface;
import common.Config;
import common.GatewayInterface;
import common.SearchResult;
import common.StatisticsCallback;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashSet;
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
 * * <p>Responsabilidades principais:</p>
 * <ul>
 * <li>Gerir fila de URLs para indexação (produtor-consumidor com Downloaders)</li>
 * <li>Distribuir pesquisas pelos Barrels usando round-robin com failover</li>
 * <li>Agregar estatísticas do sistema e notificar Clientes via callbacks</li>
 * <li>Registar URLs indexados em ficheiro de log</li>
 * <li>Implementar balanceamento de carga e tolerância a falhas</li>
 * </ul>
 * * <p>Thread-safety: Utiliza estruturas concorrentes (ConcurrentHashMap, CopyOnWriteArrayList)
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
     * * <p>Sequência de inicialização:</p>
     * <ol>
     * <li>Inicializa estruturas de dados (queues, maps, sets)</li>
     * <li>Tenta recuperar URL queue dos Barrels (tolerância a falhas)</li>
     * <li>Conecta aos Barrels via RMI</li>
     * <li>Inicia thread de monitorização de estatísticas</li>
     * </ol>
     * * @param barrelNames Lista de nomes RMI dos Barrels disponíveis
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
        
        // Tenta recuperar URL queue dos Barrels
        recoverURLQueueFromBarrels();
        
        connectToBarrels();
        
        startStatisticsMonitoringThread();
    }

    /**
     * Conecta (ou reconecta) a todos os Barrels configurados via RMI Registry.
     * Implementa retry com espera para ambientes distribuídos onde Barrels
     * podem demorar a iniciar ou estar em máquinas diferentes.
     * * <p>Estratégia de retry:</p>
     * <ul>
     * <li>Tenta conectar a cada Barrel até 10 vezes</li>
     * <li>Espera 2 segundos entre cada tentativa</li>
     * <li>Barrels que não estão acessíveis são ignorados</li>
     * </ul>
     */
    private void connectToBarrels() {
        barrels.clear();
        Registry registry;
        try {
            // Usa configuração explícita de host e porta
            String rmiHost = Config.getRmiHost();
            int rmiPort = Config.getRmiPort();
            registry = LocateRegistry.getRegistry(rmiHost, rmiPort);
            
            int maxRetries = 10;
            int retryDelay = 2000; // 2 segundos
            
            for (String name : barrelNames) {
                boolean connected = false;
                
                for (int attempt = 1; attempt <= maxRetries && !connected; attempt++) {
                    try {
                        BarrelInterface barrel = (BarrelInterface) registry.lookup(name);
                        barrels.add(barrel);
                        System.out.println("[Gateway] Ligado com sucesso a " + name);
                        connected = true;
                    } catch (Exception e) {
                        if (attempt < maxRetries) {
                            System.out.println("[Gateway] Tentativa " + attempt + "/" + maxRetries + 
                                             " para conectar a " + name + " falhou. A aguardar " + 
                                             (retryDelay/1000) + "s...");
                            try {
                                Thread.sleep(retryDelay);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        } else {
                            System.err.println("[Gateway] Falha ao ligar a " + name + 
                                             " apos " + maxRetries + " tentativas: " + e.getMessage());
                        }
                    }
                }
            }
            
            if (barrels.isEmpty()) {
                System.err.println("[Gateway]   AVISO: Nenhum Barrel conectado!");
            } else {
                System.out.println("[Gateway] Conectado a " + barrels.size() + " de " + 
                                 barrelNames.size() + " Barrel(s) configurados");
            }
            
        } catch (RemoteException e1) {
             System.err.println("[Gateway] Falha ao obter RMI registry: " + e1.getMessage());
        }
    }

    /**
     * Obtém o próximo Barrel a usar para pesquisa usando algoritmo round-robin.
     * Garante distribuição uniforme de carga entre Barrels ativos.
     * * @return Referência RMI para um Barrel, ou null se nenhum estiver disponível
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
     * * <p>Implementa deduplicação de URLs (ignora duplicados) e persiste
     * todos os URLs indexados em ficheiro de log.</p>
     * * <p><b>Backup automático:</b> Envia backup da URL queue para os Barrels
     * imediatamente após adicionar o URL (event-driven).</p>
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
            
            // Envia backup imediato da URL queue para os Barrels
            backupURLQueueToBarrels();
            
            notifyStatisticsChange();
        }
    }

    /**
     * {@inheritDoc}
     * * <p>Implementa:</p>
     * <ul>
     * <li>Round-robin para balanceamento de carga</li>
     * <li>Failover automático se um Barrel falhar</li>
     * <li>Atualização de estatísticas (top searches, tempos de resposta)</li>
     * <li>Ordenação final por relevância (backlinks)</li>
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
                    System.out.println("[Gateway] Nenhum Storage Barrel está disponível.");
                    return new ArrayList<>();
                }
                barrel = getNextBarrel();
                if (barrel == null) {
                     System.out.println("[Gateway] Nenhum Storage Barrel disponível após reconexão.");
                     return new ArrayList<>();
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

                // CORREÇÃO APLICADA AQUI:
                // Converter para ArrayList para permitir ordenação (evita UnsupportedOperationException
                // se o Barrel retornar uma lista imutável como List.of())
                if (results.isEmpty()) {
                    return new ArrayList<>();
                }
                results = new ArrayList<>(results);

                results.sort((r1, r2) -> Integer.compare(r2.getRelevance(), r1.getRelevance()));

                return results;
            } catch (RemoteException e) {
                System.err.println("[Gateway] Falha ao contactar Barrel. A tentar o próximo...");
                barrels.remove(barrel);
            }
        }
        
        System.out.println("[Gateway] Falha ao contactar todos os Storage Barrels disponíveis.");
        return new ArrayList<>();
    }

    /**
     * {@inheritDoc}
     * * <p>Implementa failover automático tentando múltiplos Barrels se necessário.</p>
     */
    @Override
    public List<String> getBacklinks(String url) throws RemoteException {
        int maxRetries = barrels.size();

        for (int i = 0; i < maxRetries; i++) {
            BarrelInterface barrel = getNextBarrel();
             if (barrel == null) {
                connectToBarrels();
                if (barrels.isEmpty()) {
                    System.out.println("[Gateway] Nenhum Storage Barrel está disponível.");
                    return new ArrayList<>();
                }
                barrel = getNextBarrel();
                 if (barrel == null) {
                     System.out.println("[Gateway] Nenhum Storage Barrel disponível após reconexão.");
                     return new ArrayList<>();
                }
            }

            try {
                return barrel.getBacklinks(url);
            } catch (RemoteException e) {
                System.err.println("[Gateway] Falha ao obter backlinks. A tentar o próximo...");
                barrels.remove(barrel);
            }
        }
        
        System.out.println("[Gateway] Falha ao contactar todos os Storage Barrels para obter backlinks.");
        return new ArrayList<>();
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
     * * <p>Implementa o padrão produtor-consumidor: remove e retorna um URL da fila.</p>
     * * <p><b>Backup automático:</b> Se um URL foi removido, envia backup atualizado
     * da queue para os Barrels (event-driven).</p>
     */
    @Override
    public String getURLToCrawl() throws RemoteException {
        String url = urlQueue.poll();
        
        // Se removeu um URL, envia backup atualizado
        if (url != null) {
            backupURLQueueToBarrels();
        }
        
        return url;
    }
    
    /**
     * {@inheritDoc}
     * * <p>Envia estatísticas iniciais imediatamente após registo bem-sucedido.</p>
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
     * * @param statistics String formatada com as estatísticas
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
     * * @return String formatada com estatísticas completas
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
     * Recupera o backup da URL queue guardado nos Barrels após reinício da Gateway.
     * Implementa tolerância a falhas permitindo que a Gateway recupere o seu estado.
     * * <p>Estratégia de recuperação:</p>
     * <ol>
     * <li>Tenta conectar aos Barrels com retry (até 5 tentativas)</li>
     * <li>Obtém backup de cada Barrel disponível</li>
     * <li>Escolhe o backup com mais URLs (estado mais completo)</li>
     * <li>Restaura urlQueue e visitedURLs</li>
     * </ol>
     * * <p>Se nenhum Barrel tiver backup, inicia com queues vazias.</p>
     * * <p>Chamado automaticamente no construtor da Gateway.</p>
     */
    private void recoverURLQueueFromBarrels() {
        System.out.println("[Gateway] Tentando recuperar URL queue dos Barrels...");
        
        try {
            String rmiHost = Config.getRmiHost();
            int rmiPort = Config.getRmiPort();
            Registry registry = LocateRegistry.getRegistry(rmiHost, rmiPort);
            
            Queue<String> bestQueue = null;
            Set<String> bestVisited = null;
            int maxSize = 0;
            
            int maxRetries = 5;
            int retryDelay = 2000; // 2 segundos
            
            for (String barrelName : barrelNames) {
                boolean recovered = false;
                
                for (int attempt = 1; attempt <= maxRetries && !recovered; attempt++) {
                    try {
                        BarrelInterface barrel = (BarrelInterface) registry.lookup(barrelName);
                        Object[] backup = barrel.restoreURLQueue();
                        
                        if (backup != null && backup.length == 2) {
                            @SuppressWarnings("unchecked")
                            Queue<String> queue = (Queue<String>) backup[0];
                            @SuppressWarnings("unchecked")
                            Set<String> visited = (Set<String>) backup[1];
                            
                            int totalSize = queue.size() + visited.size();
                            if (totalSize > maxSize) {
                                maxSize = totalSize;
                                bestQueue = queue;
                                bestVisited = visited;
                            }
                            
                            System.out.println("[Gateway] Barrel " + barrelName + " tem backup: " + 
                                             queue.size() + " URLs pendentes, " + visited.size() + " visitados");
                            recovered = true;
                        }
                    } catch (Exception e) {
                        if (attempt < maxRetries) {
                            System.out.println("[Gateway] Tentativa " + attempt + "/" + maxRetries + 
                                             " para recuperar de " + barrelName + " falhou. A aguardar...");
                            try {
                                Thread.sleep(retryDelay);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        } else {
                            System.err.println("[Gateway] Nao foi possivel recuperar de " + barrelName + 
                                             " apos " + maxRetries + " tentativas");
                        }
                    }
                }
            }
            
            if (bestQueue != null && bestVisited != null) {
                urlQueue.addAll(bestQueue);
                visitedURLs.addAll(bestVisited);
                System.out.println("[Gateway] URL queue recuperada com sucesso! " + 
                                 urlQueue.size() + " URLs pendentes, " + visitedURLs.size() + " URLs visitados");
            } else {
                System.out.println("[Gateway] Nenhum backup encontrado. A iniciar com queue vazia.");
            }
            
        } catch (Exception e) {
            System.err.println("[Gateway] Erro ao recuperar URL queue: " + e.getMessage());
        }
    }
    
    /**
     * Envia backup da URL queue para todos os Barrels disponíveis.
     * Implementa backup event-driven: chamado sempre que a queue é modificada.
     * * <p>Pontos de invocação:</p>
     * <ul>
     * <li>{@link #indexNewURL(String)} - Quando novo URL é adicionado</li>
     * <li>{@link #getURLToCrawl()} - Quando URL é removido (consumido)</li>
     * </ul>
     * * <p><b>Execução assíncrona:</b> Cria thread separada para não bloquear
     * a operação principal. Barrels offline são ignorados silenciosamente.</p>
     * * <p>Garante que todos os Barrels têm sempre o estado mais recente
     * da URL queue para permitir recuperação da Gateway.</p>
     */
    private void backupURLQueueToBarrels() {
        // Executa em thread separada para não bloquear
        new Thread(() -> {
            // Se não há Barrels conectados, tenta reconectar
            if (barrels.isEmpty()) {
                System.out.println("[Gateway]   Lista de Barrels vazia, a tentar conectar...");
                connectToBarrels();
                
                // Se ainda está vazia após reconexão, desiste
                if (barrels.isEmpty()) {
                    System.err.println("[Gateway] Nao foi possivel conectar a nenhum Barrel para enviar backup!");
                    return;
                }
            }
            
            System.out.println("[Gateway] Enviando backup da URL queue para " + barrels.size() + " Barrel(s)...");
            System.out.println("[Gateway] URLs pendentes: " + urlQueue.size() + ", URLs visitados: " + visitedURLs.size());
            int successCount = 0;
            int failCount = 0;
            
            for (int i = 0; i < barrels.size(); i++) {
                BarrelInterface barrel = barrels.get(i);
                String barrelName = (i < barrelNames.size()) ? barrelNames.get(i) : "Barrel#" + i;
                try {
                    barrel.backupURLQueue(new ConcurrentLinkedQueue<>(urlQueue), 
                                        new HashSet<>(visitedURLs));
                    successCount++;
                    System.out.println("[Gateway] Backup enviado com sucesso para " + barrelName);
                } catch (RemoteException e) {
                    failCount++;
                    System.err.println("[Gateway] Falha ao enviar backup para " + barrelName + ": " + e.getMessage());
                    // Remove barrel da lista se falhou
                    barrels.remove(barrel);
                }
            }
            
            System.out.println("[Gateway] Backup completo: " + successCount + " sucessos, " + failCount + " falhas");
        }).start();
    }

    /**
     * Método principal para iniciar a Gateway.
     * Configura RMI, conecta ao Registry e exporta a Gateway como serviço RMI.
     * * @param args Argumentos de linha de comando (não utilizados)
     */
    public static void main(String[] args) {
        System.setProperty("java.security.policy", "security.policy");
        
        // Lê configurações do ficheiro config.properties
        String gatewayHost = Config.getGatewayHost();
        System.setProperty("java.rmi.server.hostname", gatewayHost);

        try {
            String gatewayName = Config.getGatewayName();
            List<String> barrelNames = Config.getBarrelsList();
            String rmiHost = Config.getRmiHost();
            int rmiPort = Config.getRmiPort();
            
            System.out.println("[Gateway] A iniciar com nome: " + gatewayName);
            System.out.println("[Gateway] Modo de execução: " + Config.getDeploymentMode());
            System.out.println("[Gateway] Hostname RMI configurado: " + gatewayHost);
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