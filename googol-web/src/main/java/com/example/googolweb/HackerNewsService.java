package com.example.googolweb;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@Service
public class HackerNewsService {

    private final RestClient restClient;
    private final GoogolService googolService;

    public HackerNewsService(GoogolService googolService) {
        this.restClient = RestClient.create();
        this.googolService = googolService;
    }

    // Método principal: Procura e indexa
    public int searchAndIndex(String query) {
        if (query == null || query.isBlank()) return 0;
        String term = query.toLowerCase();

        System.out.println(">>> [HackerNews] A obter Top Stories...");
        
        // 1. Obter os IDs das Top Stories (retorna lista de inteiros)
        List<Integer> topStoryIds = restClient.get()
                .uri("https://hacker-news.firebaseio.com/v0/topstories.json")
                .retrieve()
                .body(List.class);

        if (topStoryIds == null) return 0;

        int indexedCount = 0;
        // Limitamos a 20 histórias para não bloquear o servidor muito tempo
        int limit = Math.min(topStoryIds.size(), 20); 

        for (int i = 0; i < limit; i++) {
            Integer id = topStoryIds.get(i);
            
            // 2. Obter detalhes de cada história
            try {
                Map story = restClient.get()
                        .uri("https://hacker-news.firebaseio.com/v0/item/" + id + ".json")
                        .retrieve()
                        .body(Map.class);

                if (story != null && story.containsKey("url")) {
                    String title = (String) story.getOrDefault("title", "");
                    String text = (String) story.getOrDefault("text", ""); // Alguns têm texto
                    String url = (String) story.get("url");

                    // 3. Verificar se contêm o termo pesquisado
                    if (title.toLowerCase().contains(term) || text.toLowerCase().contains(term)) {
                        System.out.println(">>> [HackerNews] Encontrado: " + title);
                        System.out.println(">>> [HackerNews] A indexar: " + url);
                        
                        // 4. Mandar a Gateway indexar
                        googolService.indexURL(url);
                        indexedCount++;
                    }
                }
            } catch (Exception e) {
                System.err.println("Erro ao processar história HN " + id + ": " + e.getMessage());
            }
        }
        
        return indexedCount;
    }
}