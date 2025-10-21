package pt.uc.dei.sd.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Interface RMI para a Gateway[cite: 107].
 * Esta é a única interface que o Cliente vê.
 */
public interface GatewayInterface extends Remote {

    /**
     * Submete um novo URL para ser indexado[cite: 46].
     * @param url O URL a indexar.
     * @throws RemoteException
     */
    void indexNewURL(String url) throws RemoteException;

    /**
     * Realiza uma pesquisa por um conjunto de termos[cite: 50].
     * @param query String com um ou mais termos de pesquisa.
     * @return Uma lista de resultados de pesquisa.
     * @throws RemoteException
     */
    List<SearchResult> search(String query) throws RemoteException;

    /**
     * Consulta as páginas que têm ligação para um URL específico[cite: 61].
     * @param url O URL a consultar.
     * @return Uma lista de URLs que apontam para o URL dado.
     * @throws RemoteException
     */
    List<String> getBacklinks(String url) throws RemoteException;

    /**
     * Obtém as estatísticas do sistema[cite: 64, 66].
     * @return Uma string formatada com as estatísticas.
     * @throws RemoteException
     */
    String getStatistics() throws RemoteException;

    /**
     * Método para os Downloaders obterem um URL da fila[cite: 114].
     * @return Um URL para processar, or null se a fila estiver vazia.
     * @throws RemoteException
     */
    String getURLToCrawl() throws RemoteException;
}