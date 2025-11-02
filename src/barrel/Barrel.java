package barrel;

import common.BarrelInterface;
import common.SearchResult;
import common.Config;
import common.BloomFilter;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * Implementação do Storage Barrel - componente de armazenamento do sistema Googol.
 * 
 * <p>Responsabilidades principais:</p>
 * <ul>
 *   <li>Armazenar índice invertido (palavra → conjunto de URLs)</li>
 *   <li>Armazenar backlinks (URL → conjunto de URLs que apontam para ele)</li>
 *   <li>Armazenar informações de páginas (título, citação)</li>
 *   <li>Usar Filtro de Bloom para otimizar pesquisas</li>
 *   <li>Persistir estado (Barrel primário) e sincronizar via RMI</li>
 *   <li>Responder a pesquisas da Gateway</li>
 *   <li>Receber atualizações dos Downloaders (Reliable Multicast)</li>
 * </ul>
 * 
 * <p><b>Sistema Híbrido de Persistência:</b></p>
 * <ul>
 *   <li>Barrel 0 (primário): Auto-save periódico em ficheiro + RMI</li>
 *   <li>Outros Barrels: Sincronizam via RMI com outros Barrels ou ficheiro</li>
 * </ul>
 * 
 * <p>Thread-safety: Todas as estruturas usam ConcurrentHashMap e operações atómicas.</p>
 */
public class Barrel extends UnicastRemoteObject implements BarrelInterface {

    private final Map<String, Set<String>> invertedIndex;
    private final Map<String, Set<String>> backlinks;
    private final Map<String, SearchResult> pageInfo;
    
    private final BloomFilter bloomFilter;

    private final String rmiName;
    private final String stateFile;
    private final List<String> otherBarrelNames;
    private final boolean isPrimaryBarrel;
    
    private Thread autoSaveThread;

    /**
     * Cria um novo Barrel e inicializa todas as estruturas de dados.
     * Se for o Barrel primário (índice 0), inicia thread de auto-save.
     * Tenta sincronizar estado de outros Barrels ou ficheiro.
     * 
     * @param rmiName Nome RMI deste Barrel (ex: "GoogolBarrel0")
     * @param otherBarrelNames Lista de nomes RMI dos outros Barrels (para sincronização)
     * @throws RemoteException Se houver falha ao exportar o objeto RMI
     */
    protected Barrel(String rmiName, List<String> otherBarrelNames) throws RemoteException {
        super();
        this.rmiName = rmiName;
        this.stateFile = "barrel_state_primary.ser";
        this.otherBarrelNames = otherBarrelNames != null ? otherBarrelNames : new ArrayList<>();
        
        List<String> allBarrels = Config.getBarrelsList();
        this.isPrimaryBarrel = !allBarrels.isEmpty() && allBarrels.get(0).equals(rmiName);
        
        this.invertedIndex = new ConcurrentHashMap<>();
        this.backlinks = new ConcurrentHashMap<>();
        this.pageInfo = new ConcurrentHashMap<>();
        
        int expectedElements = Config.getBloomExpectedElements();
        double falsePositiveRate = Config.getBloomFalsePositiveRate();
        this.bloomFilter = new BloomFilter(expectedElements, falsePositiveRate);
        System.out.println("[Barrel " + rmiName + "] Filtro de Bloom inicializado.");
        
        if (isPrimaryBarrel) {
            System.out.println("[Barrel " + rmiName + "] Este é o BARREL PRIMÁRIO - guardará estado em ficheiro.");
        }
        
        // Tenta sincronizar: primeiro via RMI, depois via ficheiro
        syncFromOtherBarrelOrFile();
        
        // Se for o Barrel primário, inicia thread de auto-save
        if (isPrimaryBarrel) {
            startAutoSaveThread();
        }
    }

    /**
     * {@inheritDoc}
     * 
     * <p>Implementação otimizada:</p>
     * <ol>
     *   <li>Usa Filtro de Bloom para verificação rápida de existência de palavras</li>
     *   <li>Calcula interseção de URLs para TODOS os termos</li>
     *   <li>Calcula relevância baseada no número de backlinks</li>
     *   <li>Ordena resultados por relevância (maior primeiro)</li>
     * </ol>
     */
    @Override
    public List<SearchResult> search(String[] terms) throws RemoteException {
        System.out.println("[Barrel " + rmiName + "] A processar pesquisa por: " + String.join(" ", terms));

        if (terms == null || terms.length == 0) {
            return List.of(); // Retorna lista vazia se não houver termos
        }

        // 1. Usar Filtro de Bloom para verificação rápida
        // Se o Bloom Filter diz que NÃO está, definitivamente não está!
        for (String term : terms) {
            String termLower = term.toLowerCase();
            if (!bloomFilter.mightContain(termLower)) {
                System.out.println("[Barrel " + rmiName + "] Bloom Filter: '" + termLower + "' definitivamente NÃO existe. Pesquisa cancelada.");
                return List.of(); // Poupamos tempo, não precisa buscar no HashMap
            }
        }
        
        System.out.println("[Barrel " + rmiName + "] Bloom Filter: Todos os termos PODEM existir. A verificar no índice...");

        // 2. Obter o Set de URLs para o primeiro termo.
        Set<String> urlsForFirstTerm = invertedIndex.get(terms[0].toLowerCase());
        if (urlsForFirstTerm == null) {
            // Se o primeiro termo não existe no índice, nenhuma página conterá todos os termos.
            System.out.println("[Barrel " + rmiName + "] Termo '" + terms[0] + "' não encontrado (Bloom Filter teve falso positivo).");
            return List.of(); // Retorna lista vazia
        }

        // 3. Encontrar a interseção de todos os Sets
        // Começamos com uma cópia do set do primeiro termo
        Set<String> resultSet = new HashSet<>(urlsForFirstTerm);

        // Itera pelos restantes termos
        for (int i = 1; i < terms.length; i++) {
            Set<String> urlsForThisTerm = invertedIndex.get(terms[i].toLowerCase());

            if (urlsForThisTerm == null) {
                // Se qualquer termo não for encontrado, a interseção é vazia.
                return List.of();
            }

            // Mantém apenas os URLs que também estão no set deste termo (interseção)
            resultSet.retainAll(urlsForThisTerm);

            if (resultSet.isEmpty()) {
                // Se a interseção ficar vazia a meio, podemos parar mais cedo.
                return List.of();
            }
        }

        // 4. e 5. Preparar os resultados finais com relevância
        List<SearchResult> finalResults = new ArrayList<>();
        for (String url : resultSet) {
            SearchResult result = pageInfo.get(url);
            
            if (result != null) {
                // b. Obter o número de backlinks e definir a relevância 
                Set<String> links = backlinks.get(url);
                int relevance = (links != null) ? links.size() : 0;
                result.setRelevance(relevance);
                
                finalResults.add(result);
            }
        }
        
        // Retorna a lista de SearchResults (a Gateway tratará da ordenação e paginação)
        return finalResults;
    }

    /**
     * {@inheritDoc}
     * 
     * <p>Implementação do Reliable Multicast:</p>
     * <ul>
     *   <li>Atualiza índice invertido (palavra → URLs)</li>
     *   <li>Atualiza backlinks (URL → URLs que apontam para ele)</li>
     *   <li>Armazena informações da página (título, citação)</li>
     *   <li>Adiciona palavras ao Filtro de Bloom</li>
     * </ul>
     * 
     * <p>Thread-safe: Usa operações atómicas do ConcurrentHashMap.</p>
     */
    @Override
    public void updateIndex(String url, String title, String citation, Set<String> words, Set<String> newLinksOnPage) throws RemoteException {
        System.out.println("[Barrel " + rmiName + "] A indexar URL: " + url);
        
        // Esta operação tem de ser atómica/sincronizada.
        // O uso de ConcurrentHashMap e seus métodos atómicos (put, computeIfAbsent)
        // garante a thread-safety necessária.

        // 1. Guardar a informação da página
        // Cria o SearchResult com os dados base
        pageInfo.put(url, new SearchResult(url, title, citation));

        // 2. Atualizar o índice invertido E o Filtro de Bloom
        for (String word : words) {
            String wordLower = word.toLowerCase();
            
            // Adiciona ao Filtro de Bloom (otimização para pesquisas futuras)
            bloomFilter.add(wordLower);
            
            // computeIfAbsent garante que o Set existe antes de adicionar o URL
            // ConcurrentHashMap.newKeySet() cria um Set thread-safe
            invertedIndex.computeIfAbsent(wordLower, k -> ConcurrentHashMap.newKeySet()).add(url);
        }

        // 3. Atualizar os backlinks
        // Para cada `link` em `newLinksOnPage`, adicionar `url` ao seu set de backlinks.
        for (String link : newLinksOnPage) {
            backlinks.computeIfAbsent(link, k -> ConcurrentHashMap.newKeySet()).add(url);
        }
        
        // Já não guarda em disco - os dados são replicados via reliable multicast
        // e sincronizados entre barrels quando um reinicia
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<String> getBacklinks(String url) throws RemoteException {
        // 1. Aceder ao mapa `backlinks` e obter o Set<String> para o `url`.
        Set<String> links = backlinks.get(url);
        
        // 2. Converter o Set para uma List e retornar.
        if (links != null) {
            // Retorna uma cópia para evitar problemas de concorrência se o set for modificado
            return new java.util.ArrayList<>(links);
        }
        return List.of(); // Retorna lista vazia se não houver backlinks
    }

    /**
     * {@inheritDoc}
     * 
     * <p>Retorna estatísticas formatadas incluindo estado do Filtro de Bloom.</p>
     */
    @Override
    public String getBarrelStats() throws RemoteException {
        // Retorna estatísticas incluindo o Filtro de Bloom
        return String.format("Índice: %d palavras, %d URLs. %s", 
                           invertedIndex.size(), 
                           pageInfo.size(),
                           bloomFilter.getStats());
    }
    
    @Override
    public Map<String, Set<String>> getInvertedIndex() throws RemoteException {
        // Retorna uma cópia para evitar modificações externas
        return new ConcurrentHashMap<>(invertedIndex);
    }
    
    @Override
    public Map<String, Set<String>> getBacklinksMap() throws RemoteException {
        // Retorna uma cópia para evitar modificações externas
        return new ConcurrentHashMap<>(backlinks);
    }
    
    @Override
    public Map<String, SearchResult> getPageInfoMap() throws RemoteException {
        // Retorna uma cópia para evitar modificações externas
        return new ConcurrentHashMap<>(pageInfo);
    }

    /**
     * Guarda o estado do Barrel em disco (apenas para Barrel primário).
     * Serializa as estruturas de dados principais incluindo o Bloom Filter.
     */
    private synchronized void saveState() {
        if (!isPrimaryBarrel) {
            return; // Apenas o Barrel primário guarda estado
        }
        
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(stateFile))) {
            BarrelState state = new BarrelState(
                new ConcurrentHashMap<>(invertedIndex),
                new ConcurrentHashMap<>(backlinks),
                new ConcurrentHashMap<>(pageInfo),
                bloomFilter
            );
            oos.writeObject(state);
            System.out.println("[Barrel " + rmiName + "] Estado guardado em " + stateFile);
        } catch (IOException e) {
            System.err.println("[Barrel " + rmiName + "] Erro ao guardar estado: " + e.getMessage());
        }
    }

    /**
     * Carrega o estado do Barrel a partir do ficheiro.
     * Usado como fallback se nenhum Barrel estiver disponível via RMI.
     */
    private void loadState() {
        File file = new File(stateFile);
        if (!file.exists()) {
            System.out.println("[Barrel " + rmiName + "] Ficheiro de estado não existe: " + stateFile);
            return;
        }
        
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            BarrelState state = (BarrelState) ois.readObject();
            
            // Restaura as estruturas de dados
            invertedIndex.putAll(state.invertedIndex);
            backlinks.putAll(state.backlinks);
            pageInfo.putAll(state.pageInfo);
            
            // Reconstrói o Filtro de Bloom a partir das palavras
            // (Não usamos o BloomFilter salvo porque pode estar desatualizado)
            System.out.println("[Barrel " + rmiName + "] A reconstruir Filtro de Bloom do ficheiro...");
            for (String word : state.invertedIndex.keySet()) {
                bloomFilter.add(word);
            }
            
            System.out.println("[Barrel " + rmiName + "] Estado carregado de " + stateFile);
            System.out.println("[Barrel " + rmiName + "] Restauradas " + invertedIndex.size() + 
                             " palavras, " + pageInfo.size() + " URLs.");
            System.out.println("[Barrel " + rmiName + "] " + bloomFilter.getStats());
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[Barrel " + rmiName + "] Erro ao carregar estado: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Sincroniza dados de outros Barrels via RMI ou carrega de ficheiro.
     * Estratégia: tenta RMI primeiro (dados mais atualizados), depois ficheiro (backup).
     */
    private void syncFromOtherBarrelOrFile() {
        // Estratégia 1: Tentar sincronizar via RMI com outros Barrels
        boolean syncedViaRMI = tryRMISync();
        
        if (syncedViaRMI) {
            System.out.println("[Barrel " + rmiName + "] Sincronização via RMI bem-sucedida.");
            return;
        }
        
        // Estratégia 2: Se RMI falhou, tentar carregar do ficheiro
        System.out.println("[Barrel " + rmiName + "] Sincronização RMI falhou. A tentar carregar do ficheiro...");
        loadState();
        
        if (invertedIndex.isEmpty()) {
            System.out.println("[Barrel " + rmiName + "] A começar com estado vazio.");
        }
    }
    
    /**
     * Tenta sincronizar via RMI com outros Barrels.
     * @return true se conseguiu sincronizar com sucesso
     */
    private boolean tryRMISync() {
        if (otherBarrelNames.isEmpty()) {
            System.out.println("[Barrel " + rmiName + "] Nenhum barrel para sincronizar via RMI.");
            return false;
        }

        System.out.println("[Barrel " + rmiName + "] A tentar sincronizar via RMI com outros barrels...");
        
        for (String barrelName : otherBarrelNames) {
            // Não tenta sincronizar consigo mesmo
            if (barrelName.equals(rmiName)) {
                continue;
            }
            
            try {
                // Tenta conectar ao barrel
                Registry registry = LocateRegistry.getRegistry();
                BarrelInterface otherBarrel = (BarrelInterface) registry.lookup(barrelName);
                
                System.out.println("[Barrel " + rmiName + "] Conectado a " + barrelName + ". A copiar dados...");
                
                // Obtém todos os dados do outro barrel
                Map<String, Set<String>> otherIndex = otherBarrel.getInvertedIndex();
                Map<String, Set<String>> otherBacklinks = otherBarrel.getBacklinksMap();
                Map<String, SearchResult> otherPageInfo = otherBarrel.getPageInfoMap();
                
                // Copia os dados
                invertedIndex.putAll(otherIndex);
                backlinks.putAll(otherBacklinks);
                pageInfo.putAll(otherPageInfo);
                
                // Reconstrói o Filtro de Bloom com todas as palavras do índice
                System.out.println("[Barrel " + rmiName + "] A reconstruir Filtro de Bloom...");
                for (String word : invertedIndex.keySet()) {
                    bloomFilter.add(word);
                }
                
                System.out.println("[Barrel " + rmiName + "] Sincronização completa!");
                System.out.println("[Barrel " + rmiName + "] Dados copiados: " + 
                                  pageInfo.size() + " URLs, " + 
                                  invertedIndex.size() + " palavras");
                System.out.println("[Barrel " + rmiName + "] " + bloomFilter.getStats());
                
                return true; // Sucesso - sincronização RMI funcionou
                
            } catch (Exception e) {
                System.err.println("[Barrel " + rmiName + "] Falha ao sincronizar com " + barrelName + ": " + e.getMessage());
                // Tenta o próximo barrel
            }
        }
        
        return false; // Nenhum barrel disponível via RMI
    }
    
    /**
     * Inicia uma thread que guarda o estado periodicamente (apenas no Barrel primário).
     */
    private void startAutoSaveThread() {
        int saveIntervalSeconds = Config.getInt("barrel.autosave.interval", 60); // 60 segundos padrão
        
        autoSaveThread = new Thread(() -> {
            System.out.println("[Barrel " + rmiName + "] Thread de auto-save iniciada (intervalo: " + saveIntervalSeconds + "s).");
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(saveIntervalSeconds * 1000L);
                    saveState();
                } catch (InterruptedException e) {
                    System.out.println("[Barrel " + rmiName + "] Thread de auto-save interrompida.");
                    break;
                } catch (Exception e) {
                    System.err.println("[Barrel " + rmiName + "] Erro no auto-save: " + e.getMessage());
                }
            }
        });
        
        autoSaveThread.setDaemon(true);
        autoSaveThread.setName("BarrelAutoSave-" + rmiName);
        autoSaveThread.start();
    }

    /**
     * Classe auxiliar para serialização do estado do Barrel.
     * Contém todas as estruturas de dados que precisam ser persistidas.
     */
    private static class BarrelState implements Serializable {
        private static final long serialVersionUID = 2L; // Alterado para versão 2
        
        final Map<String, Set<String>> invertedIndex;
        final Map<String, Set<String>> backlinks;
        final Map<String, SearchResult> pageInfo;
        final BloomFilter bloomFilter; // Agora incluído no estado

        BarrelState(Map<String, Set<String>> invertedIndex, 
                   Map<String, Set<String>> backlinks,
                   Map<String, SearchResult> pageInfo,
                   BloomFilter bloomFilter) {
            this.invertedIndex = invertedIndex;
            this.backlinks = backlinks;
            this.pageInfo = pageInfo;
            this.bloomFilter = bloomFilter;
        }
    }

    /**
     * Método principal para iniciar um Barrel.
     * Recebe o índice do Barrel como argumento (0, 1, 2, ...).
     * 
     * @param args Array com um elemento: índice do Barrel
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Uso: java pt.uc.dei.sd.barrel.Barrel <barrel-index>");
            System.err.println("Exemplo: java pt.uc.dei.sd.barrel.Barrel 0  (para o primeiro barrel)");
            System.err.println("         java pt.uc.dei.sd.barrel.Barrel 1  (para o segundo barrel)");
            System.exit(1);
        }
        
        // O ficheiro `security.policy` é necessário para RMI
        System.setProperty("java.security.policy", "security.policy");

        try {
            // Lê lista de barrels do config.properties
            List<String> allBarrels = Config.getBarrelsList();
            
            // O índice indica qual barrel este processo é (0, 1, 2, ...)
            int barrelIndex = Integer.parseInt(args[0]);
            
            if (barrelIndex < 0 || barrelIndex >= allBarrels.size()) {
                System.err.println("[Barrel] Índice inválido. Deve ser entre 0 e " + (allBarrels.size() - 1));
                System.exit(1);
            }
            
            // Determina o nome RMI deste barrel e dos outros
            String rmiName = allBarrels.get(barrelIndex);
            List<String> otherBarrelNames = new ArrayList<>();
            for (int i = 0; i < allBarrels.size(); i++) {
                if (i != barrelIndex) {
                    otherBarrelNames.add(allBarrels.get(i));
                }
            }
            
            System.out.println("[Barrel] A iniciar como: " + rmiName);
            System.out.println("[Barrel] Outros barrels: " + otherBarrelNames);
            
            // Define o hostname RMI para garantir comunicação local
            System.setProperty("java.rmi.server.hostname", Config.getRmiHost());
            
            Barrel barrel = new Barrel(rmiName, otherBarrelNames);
            
            // Usa configuração explícita de host e porta do config.properties
            String rmiHost = Config.getRmiHost();
            int rmiPort = Config.getRmiPort();
            System.out.println("[Barrel] A conectar ao RMI Registry em " + rmiHost + ":" + rmiPort);
            
            Registry registry;
            try {
                registry = LocateRegistry.getRegistry(rmiHost, rmiPort);
                // Testa se o registry está acessível
                registry.list();
            } catch (Exception e) {
                System.err.println("[Barrel] Registry não encontrado em " + rmiHost + ":" + rmiPort);
                System.err.println("[Barrel] Certifique-se que o RMI Registry está a correr!");
                throw e;
            }
            
            registry.rebind(rmiName, barrel);
            System.out.println("[Barrel " + rmiName + "] pronto e ligado ao RMI Registry.");
        } catch (NumberFormatException e) {
            System.err.println("[Barrel] Índice inválido. Deve ser um número.");
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("[Barrel] exceção: " + e.toString());
            e.printStackTrace();
        }
    }
}