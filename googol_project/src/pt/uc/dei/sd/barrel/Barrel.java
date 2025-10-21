package pt.uc.dei.sd.barrel;

import pt.uc.dei.sd.common.BarrelInterface;
import pt.uc.dei.sd.common.SearchResult;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList; // Importado
import java.util.HashSet;   // Importado
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementação do Storage Barrel[cite: 103].
 * Armazena o índice invertido e os backlinks.
 */
public class Barrel extends UnicastRemoteObject implements BarrelInterface {

    // Estruturas de dados principais. Têm de ser thread-safe!
    private final Map<String, Set<String>> invertedIndex; // Palavra -> Set<URL> [cite: 29]
    private final Map<String, Set<String>> backlinks;     // URL -> Set<URL que apontam para ele> [cite: 32, 60]
    private final Map<String, SearchResult> pageInfo;    // URL -> SearchResult (para título, citação)

    private final String rmiName;

    protected Barrel(String rmiName) throws RemoteException {
        super();
        this.rmiName = rmiName;
        this.invertedIndex = new ConcurrentHashMap<>();
        this.backlinks = new ConcurrentHashMap<>();
        this.pageInfo = new ConcurrentHashMap<>();
    }

    @Override
    public List<SearchResult> search(String[] terms) throws RemoteException {
        System.out.println("[Barrel " + rmiName + "] A processar pesquisa por: " + String.join(" ", terms));

        if (terms == null || terms.length == 0) {
            return List.of(); // Retorna lista vazia se não houver termos
        }

        // 1. Obter o Set de URLs para o primeiro termo.
        Set<String> urlsForFirstTerm = invertedIndex.get(terms[0].toLowerCase());
        if (urlsForFirstTerm == null) {
            // Se o primeiro termo não existe no índice, nenhuma página conterá todos os termos.
            return List.of(); // Retorna lista vazia
        }

        // 2. Encontrar a interseção de todos os Sets
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

        // 3. e 4. Preparar os resultados finais com relevância
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

    @Override
    public void updateIndex(String url, String title, String citation, Set<String> words, Set<String> newLinksOnPage) throws RemoteException {
        System.out.println("[Barrel " + rmiName + "] A indexar URL: " + url);
        
        // Esta operação tem de ser atómica/sincronizada.
        // O uso de ConcurrentHashMap e seus métodos atómicos (put, computeIfAbsent)
        // garante a thread-safety necessária.

        // 1. Guardar a informação da página
        // Cria o SearchResult com os dados base [cite: 56]
        pageInfo.put(url, new SearchResult(url, title, citation));

        // 2. Atualizar o índice invertido
        for (String word : words) {
            // computeIfAbsent garante que o Set existe antes de adicionar o URL
            // ConcurrentHashMap.newKeySet() cria um Set thread-safe
            invertedIndex.computeIfAbsent(word.toLowerCase(), k -> ConcurrentHashMap.newKeySet()).add(url);
        }

        // 3. Atualizar os backlinks [cite: 60]
        // Para cada `link` em `newLinksOnPage`, adicionar `url` ao seu set de backlinks.
        for (String link : newLinksOnPage) {
            backlinks.computeIfAbsent(link, k -> ConcurrentHashMap.newKeySet()).add(url);
        }
    }

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

    @Override
    public String getBarrelStats() throws RemoteException {
        // Retorna o número de palavras no índice e o número de URLs indexados. [cite: 66]
        return "Índice: " + invertedIndex.size() + " palavras, " + pageInfo.size() + " URLs.";
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Uso: java pt.uc.dei.sd.barrel.Barrel <rmi-name>");
            System.err.println("Exemplo: java pt.uc.dei.sd.barrel.Barrel GoogolBarrel1");
            System.exit(1);
        }

        String rmiName = args[0];
        // O ficheiro `security.policy` é necessário para RMI
        System.setProperty("java.security.policy", "security.policy");
        // System.setSecurityManager(new SecurityManager()); // Deprecado, mas pode ser necessário em JDKs antigos

        try {
            Barrel barrel = new Barrel(rmiName);
            Registry registry = LocateRegistry.getRegistry(); // Default: localhost, 1099
            registry.rebind(rmiName, barrel);

            System.out.println("[Barrel " + rmiName + "] pronto e ligado ao RMI Registry.");
        } catch (Exception e) {
            System.err.println("[Barrel " + rmiName + "] exceção: " + e.toString());
            e.printStackTrace();
        }
    }
}