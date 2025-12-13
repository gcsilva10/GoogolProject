package com.example.googolweb;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * Serviço responsável pela integração com a API externa do Hacker News.
 * <p>
 * Este serviço permite pesquisar e indexar automaticamente as histórias mais populares
 * ("Top Stories") do Hacker News que correspondam a um critério de pesquisa.
 * </p>
 * <p>Utiliza a API REST pública do Hacker News (Firebase) para obter metadados
 * e extrair URLs relevantes para serem indexados pelo sistema Googol.</p>
 */
@Service
public class HackerNewsService {

    private final RestClient restClient;
    private final GoogolService googolService;

    /**
     * Construtor do serviço com injeção de dependências.
     * Inicializa o cliente REST para fazer pedidos HTTP.
     * * @param googolService Serviço principal para enviar os URLs encontrados para a Gateway.
     */
    public HackerNewsService(GoogolService googolService) {
        this.restClient = RestClient.create();
        this.googolService = googolService;
    }

    /**
     * Pesquisa nas histórias de topo do Hacker News e indexa aquelas que contêm o termo especificado.
     * <p>O processo segue estes passos:</p>
     * <ol>
     * <li>Obtém a lista de IDs das 500 "Top Stories".</li>
     * <li>Itera sobre os primeiros 20 IDs (para performance).</li>
     * <li>Para cada ID, faz um pedido REST para obter os detalhes da história.</li>
     * <li>Verifica se o título ou texto contém o termo de pesquisa.</li>
     * <li>Se corresponder e tiver um URL, envia-o para a Gateway via {@link GoogolService#indexURL(String)}.</li>
     * </ol>
     * * @param query O termo de pesquisa para filtrar as histórias.
     * @return O número de URLs encontrados e submetidos para indexação.
     */
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
        // Limitamos a 20 histórias para não bloquear o servidor muito tempo com múltiplos pedidos HTTP
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