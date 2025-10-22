package pt.uc.dei.sd.client;

import pt.uc.dei.sd.common.GatewayInterface;
import pt.uc.dei.sd.common.SearchResult;

import java.rmi.RemoteException; // Importado
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;
import java.util.Scanner;

/**
 * Cliente RMI.
 * Interage com o utilizador e comunica com a Gateway.
 */
public class Client {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Uso: java pt.uc.dei.sd.client.Client <gateway-rmi-name>");
            System.err.println("Exemplo: java pt.uc.dei.sd.client.Client GoogolGateway");
            System.exit(1);
        }
        
        String gatewayName = args[0];
        System.setProperty("java.security.policy", "security.policy");
        // System.setSecurityManager(new SecurityManager());

        try {
            Registry registry = LocateRegistry.getRegistry(); // localhost, 1099
            GatewayInterface gateway = (GatewayInterface) registry.lookup(gatewayName);

            System.out.println("Ligado à Googol Gateway. Bem-vindo!");
            
            runMenu(gateway);

        } catch (Exception e) {
            System.err.println("Exceção no Cliente: " + e.toString());
            e.printStackTrace();
        }
    }

    private static void runMenu(GatewayInterface gateway) {
        Scanner scanner = new Scanner(System.in);
        boolean running = true;

        while (running) {
            System.out.println("\n== Menu Googol ==");
            System.out.println("1. Pesquisar");
            System.out.println("2. Indexar novo URL");
            System.out.println("3. Ver 'backlinks' de um URL");
            System.out.println("4. Ver Estatísticas");
            System.out.println("0. Sair");
            System.out.print("> ");

            String choice = scanner.nextLine();

            try {
                switch (choice) {
                    case "1": // Pesquisar [cite: 50]
                        System.out.print("Termos de pesquisa: ");
                        String query = scanner.nextLine();
                        List<SearchResult> results = gateway.search(query);
                        // Implementa a paginação 
                        displayResultsWithPagination(results, scanner);
                        break;
                        
                    case "2": // Indexar [cite: 46]
                        System.out.print("URL para indexar: ");
                        String urlToIndex = scanner.nextLine();
                        gateway.indexNewURL(urlToIndex);
                        System.out.println("URL submetido para indexação.");
                        break;
                        
                    case "3": // Backlinks [cite: 61]
                        System.out.print("URL para consultar backlinks: ");
                        String urlBacklinks = scanner.nextLine();
                        List<String> links = gateway.getBacklinks(urlBacklinks);
                        System.out.println("-- Backlinks para " + urlBacklinks + " (" + links.size() + ") --");
                        if (links.isEmpty()) {
                            System.out.println("Nenhum backlink conhecido.");
                        } else {
                            for (String link : links) {
                                System.out.println(link);
                            }
                        }
                        break;
                        
                    case "4": // Estatísticas [cite: 64]
                        System.out.println(gateway.getStatistics());
                        break;
                        
                    case "0":
                        running = false;
                        System.out.println("A sair...");
                        break;
                        
                    default:
                        System.out.println("Opção inválida.");
                        break;
                }
            } catch (RemoteException e) {
                // Lógica de retry simples: informa o utilizador e volta ao menu.
                // O utilizador pode então tentar a operação novamente.
                System.err.println("\nErro ao comunicar com a Gateway: " + e.getMessage());
                System.err.println("Tente novamente. Se o erro persistir, a Gateway pode estar offline.");
            } catch (Exception e) {
                System.err.println("\nOcorreu um erro inesperado: " + e.getMessage());
            }
        }
        scanner.close();
    }

    /**
     * Método auxiliar para apresentar resultados com paginação 10 a 10. 
     */
    private static void displayResultsWithPagination(List<SearchResult> results, Scanner scanner) {
        System.out.println("-- Resultados (" + results.size() + ") --");
        if (results.isEmpty()) {
            System.out.println("Nenhum resultado encontrado.");
            return;
        }

        for (int i = 0; i < results.size(); i++) {
            // Apresenta o resultado (SearchResult.toString() trata da formatação)
            System.out.println(results.get(i));

            // Verifica se é altura de paginar
            boolean moreResultsExist = (i + 1) < results.size();
            boolean isPageBoundary = (i + 1) % 10 == 0;

            if (isPageBoundary && moreResultsExist) {
                System.out.print("... (Mostrados " + (i + 1) + " de " + results.size() + ") Pressione ENTER para mais 10, ou 's' para parar... ");
                String next = scanner.nextLine();
                if (next.equalsIgnoreCase("s")) {
                    break; // Pára de mostrar resultados
                }
            }
        }
        System.out.println("-- Fim dos resultados --");
    }
}