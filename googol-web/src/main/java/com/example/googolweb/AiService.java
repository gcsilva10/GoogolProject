package com.example.googolweb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class AiService {

    private final RestClient restClient;

    @Value("${groq.api.key}")
    private String groqApiKey;
    
    private final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";

    public AiService() {
        this.restClient = RestClient.create();
    }

    public String generateSummary(String query, List<String> snippets) {
        if (snippets.isEmpty()) {
            return "Não há informação suficiente nos resultados para gerar um resumo.";
        }

        String prompt = String.format(
            "O utilizador pesquisou por: '%s'.\n" +
            "Com base APENAS nestes excertos de resultados de pesquisa:\n%s\n" +
            "Escreve um resumo informativo, direto e conciso (máximo 3 frases) em Português sobre o tópico pesquisado. " +
            "Não inventes factos que não estejam no texto.",
            query,
            String.join(" | ", snippets)
        );

        // CORREÇÃO AQUI: Mudámos o modelo para a versão mais recente
        Map<String, Object> requestBody = Map.of(
            "model", "llama-3.1-8b-instant",  // <--- MODELO ATUALIZADO
            "messages", List.of(
                Map.of("role", "system", "content", "És um assistente de pesquisa útil e preciso."),
                Map.of("role", "user", "content", prompt)
            ),
            "temperature", 0.5,
            "max_tokens", 200
        );

        try {
            System.out.println(">>> [AI] A contactar Groq API...");

            Map response = restClient.post()
                    .uri(GROQ_API_URL)
                    .header("Authorization", "Bearer " + groqApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            if (response != null && response.containsKey("choices")) {
                List choices = (List) response.get("choices");
                if (!choices.isEmpty()) {
                    Map firstChoice = (Map) choices.get(0);
                    Map message = (Map) firstChoice.get("message");
                    String content = (String) message.get("content");
                    return content != null ? content : "A AI não gerou texto.";
                }
            }
            return "Não foi possível obter um resumo da AI.";

        } catch (Exception e) {
            System.err.println(">>> Erro Groq API: " + e.getMessage());
            return "Indisponível de momento (Erro na API de Inteligência Artificial).";
        }
    }
}