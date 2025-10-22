package pt.uc.dei.sd.common;

import java.io.Serializable;

/**
 * Classe para transportar os resultados da pesquisa[cite: 56].
 * Tem de ser Serializable para ser enviada por RMI.
 */
public class SearchResult implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String url;
    private final String title;
    private final String citation;
    private int relevance; // Número de backlinks [cite: 59]

    public SearchResult(String url, String title, String citation) {
        this.url = url;
        this.title = title;
        this.citation = citation;
        this.relevance = 0;
    }

    // Getters
    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public String getCitation() { return citation; }
    public int getRelevance() { return relevance; }

    // Setter
    public void setRelevance(int relevance) { this.relevance = relevance; }

    @Override
    public String toString() {
        return "T: " + title + "\nU: " + url + "\nC: " + citation + "...\nRelevância: " + relevance + "\n";
    }
}