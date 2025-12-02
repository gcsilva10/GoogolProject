package common;

import java.io.Serializable;

/**
 * Classe para transportar os resultados da pesquisa entre componentes RMI.
 * Implementa Serializable para permitir transmissão via RMI.
 * 
 * <p>Contém informações sobre uma página web indexada, incluindo URL, título,
 * citação (snippet) e relevância baseada em backlinks.</p>
 */
public class SearchResult implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String url;
    private final String title;
    private final String citation;
    private int relevance;

    /**
     * Cria um novo resultado de pesquisa.
     * 
     * @param url O URL completo da página
     * @param title O título da página (extraído da tag &lt;title&gt;)
     * @param citation Um trecho do texto da página (máximo 30 palavras)
     */
    public SearchResult(String url, String title, String citation) {
        this.url = url;
        this.title = title;
        this.citation = citation;
        this.relevance = 0;
    }

    /**
     * Obtém o URL da página.
     * 
     * @return O URL completo
     */
    public String getUrl() { return url; }
    
    /**
     * Obtém o título da página.
     * 
     * @return O título extraído do HTML
     */
    public String getTitle() { return title; }
    
    /**
     * Obtém a citação (snippet) da página.
     * 
     * @return Trecho do texto (máximo 30 palavras)
     */
    public String getCitation() { return citation; }
    
    /**
     * Obtém a relevância da página baseada no número de backlinks.
     * Quanto maior o valor, mais páginas apontam para este URL.
     * 
     * @return Número de backlinks
     */
    public int getRelevance() { return relevance; }

    /**
     * Define a relevância da página (número de backlinks).
     * 
     * @param relevance O número de páginas que apontam para este URL
     */
    public void setRelevance(int relevance) { this.relevance = relevance; }

    /**
     * Retorna uma representação formatada do resultado para exibição.
     * 
     * @return String formatada com título, URL, citação e relevância
     */
    @Override
    public String toString() {
        return "T: " + title + "\nU: " + url + "\nC: " + citation + "...\nRelevância: " + relevance + "\n";
    }
}