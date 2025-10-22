package pt.uc.dei.sd.downloader;

import pt.uc.dei.sd.common.BarrelInterface;
import pt.uc.dei.sd.common.GatewayInterface;

import java.rmi.RemoteException; // Importado
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.StringTokenizer;
import java.io.IOException; // Importado para Jsoup

// Importar Jsoup (precisa do JAR no classpath)
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Componente Downloader.
 * Obtém URLs da Gateway, processa-os e envia os dados para os Barrels.
 * Vários destes podem correr em paralelo.
 */
public class Downloader implements Runnable {

    private final String gatewayRmiName;
    private final List<String> barrelRmiNames;
    
    private GatewayInterface gateway;
    // Usamos uma lista thread-safe caso precisemos de remover barrels mortos
    private final List<BarrelInterface> barrels; 

    public Downloader(String gatewayRmiName, List<String> barrelRmiNames) {
        this.gatewayRmiName = gatewayRmiName;
        this.barrelRmiNames = barrelRmiNames;
        // Usamos ArrayList por ser simples de popular; a sincronização será manual
        this.barrels = new ArrayList<>();
    }

    /**
     * Tenta ligar-se à Gateway e a todos os Barrels.
     */
    private boolean connectToServices() {
        try {
            Registry registry = LocateRegistry.getRegistry();
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
                
                // 1. Implementar lógica de crawling com Jsoup [cite: 150-172]
                try {
                    Document doc = Jsoup.connect(url).timeout(10000).get(); // 10s timeout
                    String title = doc.title();
                    String text = doc.text();

                    // 2. Extrair palavras, citação e links
                    Set<String> words = new HashSet<>();
                    StringTokenizer tokens = new StringTokenizer(text);
                    int countTokens = 0;
                    StringBuilder citationBuilder = new StringBuilder();
                    
                    while (tokens.hasMoreTokens() && countTokens++ < 50) { // Citação máx 50 palavras
                         String token = tokens.nextToken().toLowerCase();
                         if(countTokens <= 30) { // Citação de 30 palavras [cite: 6, 56]
                            citationBuilder.append(token).append(" ");
                         }
                         // TODO: Implementar "stop words" [cite: 68] (se for grupo de 3)
                         // if (!isStopWord(token)) {
                         words.add(token);
                         // }
                    }
                    String citation = citationBuilder.toString().trim();


                    Set<String> newLinksOnPage = new HashSet<>();
                    Elements links = doc.select("a[href]");
                    for (Element link : links) {
                        String absHref = link.attr("abs:href");
                        if (absHref != null && !absHref.isEmpty()) {
                            newLinksOnPage.add(absHref);
                            // Adiciona também os novos links à fila da gateway para serem indexados [cite: 48, 49]
                            gateway.indexNewURL(absHref);
                        }
                    }
                    
                    // 3. Implementar Reliable Multicast [cite: 104, 136]
                    // Envia a atualização para TODOS os barrels.
                    // Se um falhar, tem de ter lógica de retry.
                    final int MAX_RETRIES_PER_BARREL = 3;
                    final long RETRY_DELAY_MS = 1000; // 1 segundo

                    // Cria uma cópia da lista de barrels para iterar
                    List<BarrelInterface> currentBarrels;
                    synchronized (this.barrels) {
                        currentBarrels = new ArrayList<>(this.barrels);
                    }

                    for (BarrelInterface barrel : currentBarrels) {
                        boolean success = false;
                        for (int attempt = 1; attempt <= MAX_RETRIES_PER_BARREL; attempt++) {
                            if (Thread.currentThread().isInterrupted()) break;
                            try {
                                barrel.updateIndex(url, title, citation, words, newLinksOnPage);
                                success = true;
                                break; // Sucesso, passa para o próximo barrel
                            } catch (RemoteException e) {
                                System.err.println("[Downloader " + Thread.currentThread().getId() +
                                    "] Falha ao enviar update para Barrel (tentativa " + attempt + "/" + MAX_RETRIES_PER_BARREL + "): " + e.getMessage());
                                
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
                            // Falha permanente após N retentativas
                             System.err.println("[Downloader " + Thread.currentThread().getId() +
                                "] Falha PERMANENTE ao contactar barrel. A desistir deste barrel para este URL.");
                            // Numa implementação mais avançada, podíamos remover o barrel da lista principal
                            // synchronized (this.barrels) {
                            //    this.barrels.remove(barrel);
                            // }
                        }
                        if (Thread.currentThread().isInterrupted()) break; // Sai do loop de barrels
                    }
                    
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

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Uso: java pt.uc.dei.sd.downloader.Downloader <num-threads> <gateway-rmi-name> <barrel-rmi-name-1> ...");
            System.err.println("Exemplo: java pt.uc.dei.sd.downloader.Downloader 4 GoogolGateway GoogolBarrel1 GoogolBarrel2");
            System.exit(1);
        }

        System.setProperty("java.security.policy", "security.policy");
        // System.setSecurityManager(new SecurityManager());
        
        try {
            int numThreads = Integer.parseInt(args[0]);
            String gatewayName = args[1];
            List<String> barrelNames = new ArrayList<>();
            for (int i = 2; i < args.length; i++) {
                barrelNames.add(args[i]);
            }

            // Inicia várias threads de Downloader
            for (int i = 0; i < numThreads; i++) {
                Downloader downloaderTask = new Downloader(gatewayName, barrelNames);
                Thread thread = new Thread(downloaderTask);
                thread.start();
            }
            System.out.println(numThreads + " threads de Downloader iniciadas.");

        } catch (NumberFormatException e) {
            System.err.println("O primeiro argumento (<num-threads>) deve ser um número.");
        }
    }
}