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

@Controller
public class GoogolController {

    private final GoogolService googolService;
    private final HackerNewsService hackerNewsService;
    private final AiService aiService;

    public GoogolController(GoogolService googolService, 
                          HackerNewsService hackerNewsService,
                          AiService aiService) {
        this.googolService = googolService;
        this.hackerNewsService = hackerNewsService;
        this.aiService = aiService;
    }

    @GetMapping("/")
    public String index(Model model) {
        return "index";
    }

    @GetMapping("/search")
    public String search(@RequestParam(name = "query", required = false) String query,
                         @RequestParam(name = "page", defaultValue = "1") int page,
                         Model model) {
        
        if (query == null || query.trim().isEmpty()) {
            return "redirect:/";
        }

        List<SearchResult> allResults = googolService.search(query);

        int pageSize = 10;
        int totalResults = allResults.size();
        int totalPages = (int) Math.ceil((double) totalResults / pageSize);
        
        if (page < 1) page = 1;
        if (page > totalPages && totalPages > 0) page = totalPages;

        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, totalResults);
        
        List<SearchResult> pageResults = (start <= end) ? allResults.subList(start, end) : List.of();

        model.addAttribute("query", query);
        model.addAttribute("results", pageResults);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalResults", totalResults);

        return "search";
    }

    // NOVO: Página de Backlinks
    @GetMapping("/links")
    public String viewBacklinks(@RequestParam("url") String url, Model model) {
        List<String> links = googolService.getBacklinks(url);
        
        model.addAttribute("targetUrl", url);
        model.addAttribute("backlinks", links);
        
        return "links"; // Retorna links.html
    }

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

    @PostMapping("/api/hackernews")
    public String indexHackerNews(@RequestParam("query") String query, RedirectAttributes attrs) {
        new Thread(() -> hackerNewsService.searchAndIndex(query)).start();
        attrs.addFlashAttribute("message", "A procurar no Hacker News por '" + query + "'...");
        attrs.addFlashAttribute("alertClass", "alert-info");
        return "redirect:/";
    }

    @GetMapping("/api/summary")
    public ResponseEntity<Map<String, String>> getAiSummary(@RequestParam("query") String query) {
        List<SearchResult> results = googolService.search(query);
        List<String> snippets = new ArrayList<>();
        for (int i = 0; i < Math.min(results.size(), 5); i++) {
            snippets.add(results.get(i).getCitation());
        }
        String summary = aiService.generateSummary(query, snippets);
        return ResponseEntity.ok(Map.of("summary", summary));
    }
}