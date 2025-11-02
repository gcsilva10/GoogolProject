package common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Set;

/**
 * Interface RMI para os Storage Barrels.
 * Define os métodos remotos que os Barrels disponibilizam para a Gateway e Downloaders.
 * 
 * <p>Responsabilidades:</p>
 * <ul>
 *   <li>Armazenar índice invertido (palavra → URLs)</li>
 *   <li>Armazenar backlinks (URL → URLs que apontam para ele)</li>
 *   <li>Responder a pesquisas da Gateway</li>
 *   <li>Receber atualizações dos Downloaders (Reliable Multicast)</li>
 * </ul>
 */
public interface BarrelInterface extends Remote {

    /**
     * Realiza uma pesquisa por termos no índice invertido.
     * Calcula a interseção de URLs que contêm TODOS os termos.
     * 
     * @param terms Array de termos de pesquisa (já em lowercase)
     * @return Lista de SearchResult ordenada por relevância (backlinks)
     * @throws RemoteException Se houver falha na comunicação RMI
     */
    List<SearchResult> search(String[] terms) throws RemoteException;

    /**
     * Atualiza o índice invertido com informações de uma página processada.
     * Este método implementa o "reliable multicast" - é chamado por TODOS os Downloaders
     * para garantir que TODOS os Barrels têm a mesma informação.
     *
     * @param url O URL da página processada
     * @param title O título da página (tag &lt;title&gt;)
     * @param citation Uma citação da página (máximo 30 palavras)
     * @param words Conjunto de palavras encontradas na página
     * @param newLinksOnPage Conjunto de links encontrados nessa página
     * @throws RemoteException Se houver falha na comunicação RMI
     */
    void updateIndex(String url, String title, String citation, Set<String> words, Set<String> newLinksOnPage) throws RemoteException;

    /**
     * Obtém a lista de páginas que apontam para um URL específico (backlinks).
     * 
     * @param url O URL para o qual queremos saber os backlinks
     * @return Lista de URLs que contêm links para o URL fornecido
     * @throws RemoteException Se houver falha na comunicação RMI
     */
    List<String> getBacklinks(String url) throws RemoteException;

    /**
     * Obtém estatísticas parciais deste Barrel para agregação na Gateway.
     * 
     * @return String formatada com estatísticas (ex: "1500 palavras indexadas, 500 URLs")
     * @throws RemoteException Se houver falha na comunicação RMI
     */
    String getBarrelStats() throws RemoteException;
    
    /**
     * Obtém o índice invertido completo para sincronização entre Barrels.
     * Usado quando um Barrel reinicia e precisa recuperar o estado de outro Barrel ativo.
     * 
     * @return Mapa onde chave=palavra e valor=conjunto de URLs que contêm essa palavra
     * @throws RemoteException Se houver falha na comunicação RMI
     */
    java.util.Map<String, Set<String>> getInvertedIndex() throws RemoteException;
    
    /**
     * Obtém todos os backlinks para sincronização entre Barrels.
     * Usado quando um Barrel reinicia e precisa recuperar o estado de outro Barrel ativo.
     * 
     * @return Mapa onde chave=URL e valor=conjunto de URLs que apontam para ele
     * @throws RemoteException Se houver falha na comunicação RMI
     */
    java.util.Map<String, Set<String>> getBacklinksMap() throws RemoteException;
    
    /**
     * Obtém todas as informações de páginas (título, citação) para sincronização.
     * Usado quando um Barrel reinicia e precisa recuperar o estado de outro Barrel ativo.
     * 
     * @return Mapa onde chave=URL e valor=SearchResult com informações da página
     * @throws RemoteException Se houver falha na comunicação RMI
     */
    java.util.Map<String, SearchResult> getPageInfoMap() throws RemoteException;
}