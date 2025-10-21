package pt.uc.dei.sd.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Set;

/**
 * Interface RMI para os Storage Barrels[cite: 103].
 * Usada pela Gateway (para pesquisar) e pelos Downloaders (para atualizar).
 */
public interface BarrelInterface extends Remote {

    /**
     * Método de pesquisa chamado pela Gateway[cite: 98].
     * @param terms Os termos de pesquisa já separados.
     * @return Lista de resultados encontrados neste Barrel.
     * @throws RemoteException
     */
    List<SearchResult> search(String[] terms) throws RemoteException;

    /**
     * Método chamado pelos Downloaders para atualizar o índice[cite: 76, 104].
     * Esta é a base do "reliable multicast".
     *
     * @param url O URL da página processada.
     * @param title O título da página.
     * @param citation Uma citação da página.
     * @param words As palavras encontradas na página.
     * @param newLinksOnPage Os links encontrados nessa página (para o crawler).
     * @throws RemoteException
     */
    void updateIndex(String url, String title, String citation, Set<String> words, Set<String> newLinksOnPage) throws RemoteException;

    /**
     * Obtém as páginas que ligam para um dado URL[cite: 61].
     * @param url O URL a verificar.
     * @return Lista de URLs (backlinks).
     * @throws RemoteException
     */
    List<String> getBacklinks(String url) throws RemoteException;

    /**
     * Obtém estatísticas parciais deste Barrel[cite: 66].
     * @return String com o tamanho do índice deste Barrel.
     * @throws RemoteException
     */
    String getBarrelStats() throws RemoteException;
}