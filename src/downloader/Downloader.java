package downloader;

import common.BarrelInterface;
import common.GatewayInterface;
import common.Config;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.StringTokenizer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;

// Importar Jsoup (precisa do JAR no classpath)
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Implementação do Downloader - componente de crawling do sistema Googol.
 * 
 * <p>Responsabilidades principais:</p>
 * <ul>
 *   <li>Obter URLs da fila da Gateway</li>
 *   <li>Fazer download e parsing de páginas web (Jsoup)</li>
 *   <li>Extrair palavras, links, título e citação</li>
 *   <li>Enviar dados para Barrels via Reliable Multicast</li>
 *   <li>Reportar novos URLs descobertos à Gateway</li>
 *   <li>Gerir reconexões e retry de updates pendentes</li>
 * </ul>
 * 
 * <p><b>Reliable Multicast:</b> Garante que todos os Barrels recebem atualizações,
 * guardando updates pendentes em caso de falha e re-tentando quando Barrel reconecta.</p>
 * 
 * <p>Thread-safety: Usa estruturas concorrentes e gestão manual de lista de Barrels ativos.</p>
 */
public class Downloader implements Runnable {

    private final String gatewayRmiName;
    private final List<String> barrelRmiNames;
    
    private GatewayInterface gateway;
    private final List<BarrelInterface> barrels;
    
    private final Map<String, List<PendingUpdate>> pendingUpdates;
    
    /**
     * Classe interna para representar uma atualização pendente no Reliable Multicast.
     * Guarda todos os dados necessários para reenviar ao Barrel quando reconectar.
     */
    private static class PendingUpdate {
        final String url;
        final String title;
        final String citation;
        final Set<String> words;
        final Set<String> newLinksOnPage;
        final long timestamp;
        
        /**
         * Cria uma atualização pendente com timestamp atual.
         * 
         * @param url URL da página indexada
         * @param title Título da página
         * @param citation Citação extraída
         * @param words Conjunto de palavras únicas
         * @param newLinksOnPage Links descobertos na página
         */
        PendingUpdate(String url, String title, String citation, Set<String> words, Set<String> newLinksOnPage) {
            this.url = url;
            this.title = title;
            this.citation = citation;
            this.words = words;
            this.newLinksOnPage = newLinksOnPage;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Cria um novo Downloader.
     * 
     * @param gatewayRmiName Nome RMI da Gateway (ex: "GoogolGateway")
     * @param barrelRmiNames Lista de nomes RMI dos Barrels (ex: ["GoogolBarrel0", "GoogolBarrel1"])
     */
    public Downloader(String gatewayRmiName, List<String> barrelRmiNames) {
        this.gatewayRmiName = gatewayRmiName;
        this.barrelRmiNames = barrelRmiNames;
        // Usamos ArrayList por ser simples de popular; a sincronização será manual
        this.barrels = new ArrayList<>();
        this.pendingUpdates = new ConcurrentHashMap<>();
    }

    /**
     * Tenta ligar-se à Gateway e a todos os Barrels via RMI.
     * 
     * @return true se conectou com sucesso à Gateway e pelo menos um Barrel, false caso contrário
     */
    private boolean connectToServices() {
        try {
            String rmiHost = Config.getRmiHost();
            int rmiPort = Config.getRmiPort();
            Registry registry = LocateRegistry.getRegistry(rmiHost, rmiPort);
            this.gateway = (GatewayInterface) registry.lookup(gatewayRmiName);
            
            // Sincroniza o acesso à lista de barrels
            synchronized (this.barrels) {
                this.barrels.clear();
                for (String name : barrelRmiNames) {
                    try {
                        BarrelInterface barrel = (BarrelInterface) registry.lookup(name);
                        this.barrels.add(barrel);
                    } catch (Exception e) {
                        System.err.println("[Downloader] Falha ao ligar ao Barrel " + name + ": " + e.getMessage());
                    }
                }
            }
            return this.gateway != null && !this.barrels.isEmpty();
        } catch (Exception e) {
            System.err.println("[Downloader] Falha ao ligar aos serviços RMI: " + e.getMessage());
            return false;
        }
    }

    /**
     * Implementa o loop principal do Downloader.
     * 
     * <p>Fluxo de execução:</p>
     * <ol>
     *   <li>Conecta aos serviços RMI (Gateway e Barrels)</li>
     *   <li>Loop infinito: obtém URL da Gateway</li>
     *   <li>Download e parsing da página (Jsoup)</li>
     *   <li>Extração de palavras, links, título e citação</li>
     *   <li>Reliable Multicast para todos os Barrels</li>
     *   <li>Reporta novos URLs à Gateway</li>
     * </ol>
     * 
     * <p>Gere reconexões automáticas e retry de updates pendentes.</p>
     */
    @Override
    public void run() {
        System.out.println("[Downloader " + Thread.currentThread().getId() + "] a iniciar...");

        if (!connectToServices()) {
            System.err.println("[Downloader " + Thread.currentThread().getId() + "] Não foi possível ligar aos serviços. A terminar.");
            return;
        }
        
        int barrelCount;
        synchronized (this.barrels) {
            barrelCount = this.barrels.size();
        }
        System.out.println("[Downloader " + Thread.currentThread().getId() + "] Ligado a " + gatewayRmiName + " e " + barrelCount + " barrels.");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                String url = gateway.getURLToCrawl();
                
                if (url == null) {
                    // Fila vazia, espera um pouco
                    Thread.sleep(5000); // 5 segundos
                    continue;
                }

                System.out.println("[Downloader " + Thread.currentThread().getId() + "] A processar: " + url);
                
                try {
                    Document doc = Jsoup.connect(url).timeout(10000).get();
                    String title = doc.title();
                    String text = doc.text();

                    Set<String> words = new HashSet<>();
                    StringTokenizer tokens = new StringTokenizer(text);
                    int countTokens = 0;
                    StringBuilder citationBuilder = new StringBuilder();
                    
                    while (tokens.hasMoreTokens() && countTokens++ < 50) {
                         String token = tokens.nextToken().toLowerCase();
                         if(countTokens <= 30) {
                            citationBuilder.append(token).append(" ");
                         }
                         words.add(token);
                    }
                    String citation = citationBuilder.toString().trim();


                    Set<String> newLinksOnPage = new HashSet<>();
                    Elements links = doc.select("a[href]");
                    for (Element link : links) {
                        String absHref = link.attr("abs:href");
                        if (absHref != null && !absHref.isEmpty()) {
                            newLinksOnPage.add(absHref);
                            gateway.indexNewURL(absHref);
                        }
                    }
                    
                    final int MAX_RETRIES_PER_BARREL = 3;
                    final long RETRY_DELAY_MS = 1000;

                    List<BarrelInterface> currentBarrels;
                    List<String> currentBarrelNames;
                    synchronized (this.barrels) {
                        currentBarrels = new ArrayList<>(this.barrels);
                        currentBarrelNames = new ArrayList<>(this.barrelRmiNames);
                    }

                    for (int barrelIndex = 0; barrelIndex < currentBarrels.size(); barrelIndex++) {
                        BarrelInterface barrel = currentBarrels.get(barrelIndex);
                        String barrelName = barrelIndex < currentBarrelNames.size() ? 
                                          currentBarrelNames.get(barrelIndex) : "BarrelDesconhecido";
                        
                        boolean success = false;
                        for (int attempt = 1; attempt <= MAX_RETRIES_PER_BARREL; attempt++) {
                            if (Thread.currentThread().isInterrupted()) break;
                            try {
                                barrel.updateIndex(url, title, citation, words, newLinksOnPage);
                                success = true;
                                break; // Sucesso, passa para o próximo barrel
                            } catch (RemoteException e) {
                                System.err.println("[Downloader " + Thread.currentThread().getId() +
                                    "] Falha ao enviar update para " + barrelName + " (tentativa " + attempt + "/" + MAX_RETRIES_PER_BARREL + "): " + e.getMessage());
                                
                                if (attempt < MAX_RETRIES_PER_BARREL) {
                                    try {
                                        Thread.sleep(RETRY_DELAY_MS * attempt); // Backoff
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt(); // Preserva o status de interrupção
                                        break; // Sai do loop de retry se a thread for interrompida
                                    }
                                }
                            }
                        }
                        
                        if (!success && !Thread.currentThread().isInterrupted()) {
                            System.err.println("[Downloader " + Thread.currentThread().getId() +
                                "] GUARDANDO atualização pendente para " + barrelName + " (URL: " + url + ")");
                            
                            PendingUpdate pending = new PendingUpdate(url, title, citation, 
                                                                     new HashSet<>(words), 
                                                                     new HashSet<>(newLinksOnPage));
                            pendingUpdates.computeIfAbsent(barrelName, k -> new ArrayList<>()).add(pending);
                            
                            // Remove o barrel que falhou da lista (referência obsoleta)
                            synchronized (this.barrels) {
                                this.barrels.remove(barrel);
                                System.out.println("[Downloader " + Thread.currentThread().getId() +
                                    "] Barrel " + barrelName + " removido da lista ativa. Tentará reconectar.");
                            }
                        }
                        if (Thread.currentThread().isInterrupted()) break; // Sai do loop de barrels
                    }
                    
                    // Tenta enviar atualizações pendentes para Barrels que possam ter voltado
                    retryPendingUpdates();
                    
                    System.out.println("[Downloader " + Thread.currentThread().getId() + "] Terminou de processar: " + url);

                } catch (IOException e) {
                    System.err.println("[Downloader " + Thread.currentThread().getId() + "] Falha de I/O ao processar URL " + url + ": " + e.getMessage());
                } catch (Exception e) {
                    System.err.println("[Downloader " + Thread.currentThread().getId() + "] Falha inesperada ao processar URL " + url + ": " + e.getMessage());
                }

            } catch (RemoteException e) {
                System.err.println("[Downloader] Perdeu ligação à Gateway. A tentar reconectar...");
                if (!connectToServices()) {
                    try { Thread.sleep(10000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserva o status de interrupção
                System.out.println("[Downloader " + Thread.currentThread().getId() + "] foi interrompido. A terminar.");
            }
        }
    }

    /**
     * Implementa o Reliable Multicast tentando reenviar atualizações pendentes.
     * 
     * <p>Estratégia:</p>
     * <ul>
     *   <li>Tenta reconectar a Barrels que falharam anteriormente</li>
     *   <li>Reenvia todas as atualizações guardadas em fila</li>
     *   <li>Remove da fila em caso de sucesso</li>
     *   <li>Re-adiciona Barrel à lista ativa quando bem-sucedido</li>
     * </ul>
     * 
     * <p>Garante que todos os Barrels eventualmente recebem todas as atualizações.</p>
     */
    private void retryPendingUpdates() {
        if (pendingUpdates.isEmpty()) {
            return; // Nada pendente
        }

        System.out.println("[Downloader " + Thread.currentThread().getId() + 
                          "] Tentando reenviar " + pendingUpdates.size() + " lote(s) de atualizações pendentes...");

        // Para cada Barrel com atualizações pendentes
        for (String barrelName : new ArrayList<>(pendingUpdates.keySet())) {
            List<PendingUpdate> updates = pendingUpdates.get(barrelName);
            if (updates == null || updates.isEmpty()) {
                continue;
            }

            // Tenta reconectar ao Barrel
            try {
                String rmiHost = Config.getRmiHost();
                int rmiPort = Config.getRmiPort();
                Registry registry = LocateRegistry.getRegistry(rmiHost, rmiPort);
                BarrelInterface barrel = (BarrelInterface) registry.lookup(barrelName);
                
                System.out.println("[Downloader " + Thread.currentThread().getId() + 
                                  "] Barrel " + barrelName + " voltou! A reenviar " + updates.size() + " atualizações...");

                // Adiciona o barrel de volta à lista ativa
                synchronized (this.barrels) {
                    if (!this.barrels.contains(barrel)) {
                        this.barrels.add(barrel);
                        System.out.println("[Downloader " + Thread.currentThread().getId() + 
                                          "] Barrel " + barrelName + " adicionado de volta à lista ativa.");
                    }
                }

                // Tenta enviar todas as atualizações pendentes
                List<PendingUpdate> successfullySent = new ArrayList<>();
                for (PendingUpdate update : updates) {
                    try {
                        barrel.updateIndex(update.url, update.title, update.citation, 
                                         update.words, update.newLinksOnPage);
                        successfullySent.add(update);
                        System.out.println("[Downloader " + Thread.currentThread().getId() + 
                                          "] Reenviado com sucesso: " + update.url + " para " + barrelName);
                    } catch (RemoteException e) {
                        // Esta atualização específica falhou, mas continuamos com as outras
                        System.err.println("[Downloader " + Thread.currentThread().getId() + 
                                          "] Falha ao reenviar " + update.url + " para " + barrelName);
                        break; // Se uma falha, provavelmente o barrel caiu novamente
                    }
                }

                // Remove as atualizações que foram enviadas com sucesso
                if (!successfullySent.isEmpty()) {
                    updates.removeAll(successfullySent);
                    if (updates.isEmpty()) {
                        pendingUpdates.remove(barrelName);
                        System.out.println("[Downloader " + Thread.currentThread().getId() + 
                                          "] Todas as atualizacoes pendentes foram enviadas para " + barrelName);
                    }
                }

            } catch (Exception e) {
                // Barrel ainda não está disponível, tenta na próxima vez
                // Não imprime erro para não poluir os logs
            }
        }
    }

    /**
     * Método principal para iniciar o sistema de Downloaders.
     * Lê configurações e inicia múltiplas threads conforme config.properties.
     * 
     * @param args Argumentos de linha de comando (não utilizados)
     */
    public static void main(String[] args) {
        System.setProperty("java.security.policy", "security.policy");
        
        try {
            // Lê configurações do ficheiro config.properties
            int numThreads = Config.getDownloaderThreads();
            String gatewayName = Config.getGatewayName();
            List<String> barrelNames = Config.getBarrelsList();

            System.out.println("[Downloader] A iniciar com " + numThreads + " threads");
            System.out.println("[Downloader] Gateway: " + gatewayName);
            System.out.println("[Downloader] Barrels: " + barrelNames);

            // Inicia várias threads de Downloader
            for (int i = 0; i < numThreads; i++) {
                Downloader downloaderTask = new Downloader(gatewayName, barrelNames);
                Thread thread = new Thread(downloaderTask);
                thread.start();
            }
            System.out.println(numThreads + " threads de Downloader iniciadas.");

        } catch (Exception e) {
            System.err.println("[Downloader] Erro ao iniciar: " + e.getMessage());
            e.printStackTrace();
        }
    }
}