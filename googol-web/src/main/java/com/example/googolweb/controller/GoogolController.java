package com.example.googolweb.controller;

import com.example.googolweb.AiService;
import com.example.googolweb.GoogolService;
import com.example.googolweb.HackerNewsService;
import common.SearchResult;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Controlador MVC principal da aplicação web Googol.
 * * <p>Esta classe é responsável por:</p>
 * <ul>
 * <li>Gerir os pedidos HTTP recebidos do browser (GET e POST).</li>
 * <li>Interagir com a camada de serviço ({@link GoogolService}) para comunicar com o Backend RMI.</li>
 * <li>Coordenar serviços externos como Hacker News e AI.</li>
 * <li>Preparar os dados (Model) e selecionar os templates HTML (View) para renderização.</li>
 * </ul>
 */
@Controller
public class GoogolController {

    private final GoogolService googolService;
    private final HackerNewsService hackerNewsService;
    private final AiService aiService;

    /**
     * Construtor com injeção de dependências.
     * O Spring Boot injeta automaticamente as instâncias dos serviços necessários.
     * * @param googolService Serviço de comunicação com a Gateway RMI.
     * @param hackerNewsService Serviço de integração com a API do Hacker News.
     * @param aiService Serviço de integração com a API de Inteligência Artificial.
     */
    public GoogolController(GoogolService googolService, 
                          HackerNewsService hackerNewsService,
                          AiService aiService) {
        this.googolService = googolService;
        this.hackerNewsService = hackerNewsService;
        this.aiService = aiService;
    }

    /**
     * Processa pedidos para a página inicial (root).
     * * @param model Modelo do Spring para passar dados à view (não usado aqui, mas disponível).
     * @return O nome do template HTML a renderizar ("index").
     */
    @GetMapping("/")
    public String index(Model model) {
        return "index";
    }

    /**
     * Processa pedidos de pesquisa de páginas.
     * Contacta a Gateway via RMI para obter resultados e aplica paginação local.
     * * @param query Termos de pesquisa inseridos pelo utilizador.
     * @param page Número da página atual para paginação (padrão é 1).
     * @param model Modelo para enviar os resultados e metadados de paginação para a view.
     * @return O nome do template HTML a renderizar ("search") ou redireciona para "/" se a query for vazia.
     */
    @GetMapping("/search")
    public String search(@RequestParam(name = "query", required = false) String query,
                         @RequestParam(name = "page", defaultValue = "1") int page,
                         Model model) {
        
        if (query == null || query.trim().isEmpty()) {
            return "redirect:/";
        }

        // Obtém todos os resultados da Gateway via RMI
        List<SearchResult> allResults = googolService.search(query);

        // Lógica de Paginação (10 resultados por página)
        int pageSize = 10;
        int totalResults = allResults.size();
        int totalPages = (int) Math.ceil((double) totalResults / pageSize);
        
        // Validação da página atual
        if (page < 1) page = 1;
        if (page > totalPages && totalPages > 0) page = totalPages;

        // Cria sublista para a página atual
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, totalResults);
        
        List<SearchResult> pageResults = (start <= end) ? allResults.subList(start, end) : List.of();

        // Adiciona atributos ao modelo para o Thymeleaf usar
        model.addAttribute("query", query);
        model.addAttribute("results", pageResults);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalResults", totalResults);

        return "search";
    }

    /**
     * Apresenta a lista de páginas que contêm links para um determinado URL (backlinks).
     * * @param url O URL alvo para o qual queremos ver os backlinks.
     * @param model Modelo para enviar a lista de links para a view.
     * @return O nome do template HTML a renderizar ("links").
     */
    @GetMapping("/links")
    public String viewBacklinks(@RequestParam("url") String url, Model model) {
        List<String> links = googolService.getBacklinks(url);
        
        model.addAttribute("targetUrl", url);
        model.addAttribute("backlinks", links);
        
        return "links";
    }

    /**
     * Processa o pedido manual de indexação de um novo URL.
     * Envia o URL para a Gateway via RMI.
     * * @param url O URL a ser indexado.
     * @param attrs Atributos para passar mensagens de sucesso/erro após o redirecionamento (Flash Attributes).
     * @return Redireciona de volta para a página inicial.
     */
    @PostMapping("/index")
    public String indexUrl(@RequestParam("url") String url, RedirectAttributes attrs) {
        boolean success = googolService.indexURL(url);
        
        if (success) {
            attrs.addFlashAttribute("message", "URL enviado para indexação com sucesso!");
            attrs.addFlashAttribute("alertClass", "alert-success");
        } else {
            attrs.addFlashAttribute("message", "Erro ao conectar à Gateway.");
            attrs.addFlashAttribute("alertClass", "alert-danger");
        }
        return "redirect:/";
    }

    /**
     * Inicia a indexação de notícias do Hacker News baseadas num termo de pesquisa.
     * A operação é executada assincronamente numa nova thread para não bloquear a interface.
     * * @param query Termo para filtrar as notícias do Hacker News.
     * @param attrs Atributos para passar feedback ao utilizador.
     * @return Redireciona de volta para a página inicial.
     */
    @PostMapping("/api/hackernews")
    public String indexHackerNews(@RequestParam("query") String query, RedirectAttributes attrs) {
        // Executa em thread separada para não bloquear o servidor web enquanto faz pedidos REST
        new Thread(() -> {
            hackerNewsService.searchAndIndex(query);
        }).start();
        
        attrs.addFlashAttribute("message", "A procurar no Hacker News por '" + query + "'... Os URLs encontrados serão indexados automaticamente.");
        attrs.addFlashAttribute("alertClass", "alert-info");
        
        return "redirect:/";
    }

    /**
     * Endpoint REST (API) para gerar um resumo inteligente dos resultados da pesquisa.
     * É chamado via AJAX (JavaScript) pela página de resultados para não atrasar o carregamento inicial.
     * * <p>Fluxo:</p>
     * <ol>
     * <li>Obtém resultados frescos da Gateway RMI.</li>
     * <li>Extrai as citações (snippets) dos top 5 resultados.</li>
     * <li>Envia para o {@link AiService} para gerar texto com LLM.</li>
     * </ol>
     * * @param query O termo de pesquisa original.
     * @return Um JSON contendo o texto do resumo gerado.
     */
    @GetMapping("/api/summary")
    public ResponseEntity<Map<String, String>> getAiSummary(@RequestParam("query") String query) {
        // 1. Obter resultados
        List<SearchResult> results = googolService.search(query);
        
        // 2. Preparar contexto (Top 5 snippets)
        List<String> snippets = new ArrayList<>();
        for (int i = 0; i < Math.min(results.size(), 5); i++) {
            snippets.add(results.get(i).getCitation());
        }
        
        // 3. Gerar resumo via AI
        String summary = aiService.generateSummary(query, snippets);
        
        // 4. Retornar JSON
        return ResponseEntity.ok(Map.of("summary", summary));
    }
}