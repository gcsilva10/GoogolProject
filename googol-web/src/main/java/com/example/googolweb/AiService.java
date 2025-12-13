package com.example.googolweb;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Serviço de integração com Inteligência Artificial (LLMs).
 * <p>
 * Este serviço é responsável por contactar uma API externa compatível com OpenAI (neste caso, Groq)
 * para gerar resumos contextualizados ("Análise Inteligente") baseados nos resultados da pesquisa.
 * </p>
 * <p>Utiliza o modelo <code>llama-3.1-8b-instant</code> pela sua velocidade e eficiência.</p>
 */
@Service
public class AiService {

    private final RestClient restClient;

    /**
     * Chave da API (Groq) injetada a partir do ficheiro de configuração.
     * Deve ser definida em <code>config.properties</code> ou variável de ambiente.
     */
    @Value("${groq.api.key}")
    private String groqApiKey;
    
    private final String GROQ_API_URL = "https://api.groq.com/openai/v1/chat/completions";

    /**
     * Construtor do serviço.
     * Inicializa o cliente REST do Spring.
     */
    public AiService() {
        this.restClient = RestClient.create();
    }

    /**
     * Gera um resumo textual em Português com base nos resultados de pesquisa fornecidos.
     * <p>Constrói um prompt que inclui a query original e os "snippets" (excertos)
     * dos resultados mais relevantes, instruindo o modelo a sintetizar a informação.</p>
     * * @param query O termo de pesquisa original introduzido pelo utilizador.
     * @param snippets Lista de excertos de texto dos primeiros resultados da pesquisa.
     * @return Uma string contendo o resumo gerado pela AI, ou uma mensagem de erro/indisponibilidade.
     */
    public String generateSummary(String query, List<String> snippets) {
        if (snippets.isEmpty()) {
            return "Não há informação suficiente nos resultados para gerar um resumo.";
        }

        // Construção do Prompt (Engenharia de Prompt)
        String prompt = String.format(
            "O utilizador pesquisou por: '%s'.\n" +
            "Com base APENAS nestes excertos de resultados de pesquisa:\n%s\n" +
            "Escreve um resumo informativo, direto e conciso (máximo 3 frases) em Português sobre o tópico pesquisado. " +
            "Não inventes factos que não estejam no texto.",
            query,
            String.join(" | ", snippets)
        );

        // Corpo do pedido JSON compatível com a API de Chat Completions
        Map<String, Object> requestBody = Map.of(
            "model", "llama-3.1-8b-instant",
            "messages", List.of(
                Map.of("role", "system", "content", "És um assistente de pesquisa útil e preciso."),
                Map.of("role", "user", "content", prompt)
            ),
            "temperature", 0.5, // Reduz a "criatividade" para ser mais factual
            "max_tokens", 200
        );

        try {
            System.out.println(">>> [AI] A contactar Groq API...");

            // Envio do pedido HTTP POST
            Map response = restClient.post()
                    .uri(GROQ_API_URL)
                    .header("Authorization", "Bearer " + groqApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(Map.class);

            // Parsing da resposta JSON
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