package common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Interface RMI para a Gateway - ponto de entrada central do sistema.
 * Esta é a única interface que o Cliente vê diretamente.
 * 
 * <p>Responsabilidades:</p>
 * <ul>
 *   <li>Receber pedidos de indexação de URLs dos Clientes</li>
 *   <li>Distribuir pesquisas pelos Barrels (round-robin)</li>
 *   <li>Gerir fila de URLs para os Downloaders</li>
 *   <li>Agregar e fornecer estatísticas do sistema</li>
 *   <li>Implementar sistema de callbacks para atualizações em tempo real</li>
 * </ul>
 */
public interface GatewayInterface extends Remote {

    /**
     * Submete um novo URL para ser indexado pelo sistema.
     * O URL é adicionado à fila e será processado por um Downloader.
     * 
     * @param url O URL completo a indexar (ex: "https://www.uc.pt")
     * @throws RemoteException Se houver falha na comunicação RMI
     */
    void indexNewURL(String url) throws RemoteException;

    /**
     * Realiza uma pesquisa por um conjunto de termos.
     * A pesquisa é distribuída para um Barrel usando round-robin e
     * implementa failover automático se um Barrel falhar.
     * 
     * @param query String com um ou mais termos separados por espaços
     * @return Lista de SearchResult ordenada por relevância (número de backlinks)
     * @throws RemoteException Se houver falha na comunicação RMI ou nenhum Barrel disponível
     */
    List<SearchResult> search(String query) throws RemoteException;

    /**
     * Consulta as páginas que têm ligação para um URL específico.
     * Útil para análise de popularidade e linkage de páginas.
     * 
     * @param url O URL para o qual queremos saber os backlinks
     * @return Lista de URLs que contêm links para o URL fornecido
     * @throws RemoteException Se houver falha na comunicação RMI
     */
    List<String> getBacklinks(String url) throws RemoteException;

    /**
     * Obtém as estatísticas agregadas do sistema.
     * Inclui top 10 pesquisas, Barrels ativos e tempos de resposta.
     * 
     * @return String formatada com todas as estatísticas do sistema
     * @throws RemoteException Se houver falha na comunicação RMI
     */
    String getStatistics() throws RemoteException;

    /**
     * Fornece um URL da fila para um Downloader processar.
     * Este método implementa o padrão produtor-consumidor para distribuição de trabalho.
     * 
     * @return Um URL para processar, ou null se a fila estiver vazia
     * @throws RemoteException Se houver falha na comunicação RMI
     */
    String getURLToCrawl() throws RemoteException;
    
    /**
     * Regista um callback para receber atualizações de estatísticas em tempo real (push).
     * O Cliente receberá notificações automáticas sempre que as estatísticas mudarem.
     * 
     * @param callback O objeto callback que implementa StatisticsCallback
     * @throws RemoteException Se houver falha na comunicação RMI
     */
    void registerStatisticsCallback(StatisticsCallback callback) throws RemoteException;
    
    /**
     * Remove o registo de um callback de estatísticas.
     * O Cliente deixa de receber atualizações automáticas.
     * 
     * @param callback O objeto callback a desregistar
     * @throws RemoteException Se houver falha na comunicação RMI
     */
    void unregisterStatisticsCallback(StatisticsCallback callback) throws RemoteException;
}