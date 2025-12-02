package com.example.googolweb;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class GoogolWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(GoogolWebApplication.class, args);
    }

    @Bean
    public CommandLineRunner testConnection(GoogolService service) {
        return args -> {
            System.out.println("\n--- TESTE DE ARRANQUE ---");
            // Tenta uma operação simples RMI
            boolean result = service.indexURL("https://phet-dev.colorado.edu/html/build-an-atom/0.0.0-3/simple-text-only-test-page.html");
            if (result) {
                System.out.println("✅ RMI Funciona: Pedido enviado à Gateway.");
            } else {
                System.out.println("⚠️ RMI Falhou: Verifica se a Gateway da Meta 1 está a correr.");
            }
            System.out.println("-------------------------\n");
        };
    }
}