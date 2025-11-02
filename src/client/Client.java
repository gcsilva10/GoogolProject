package client;

import common.GatewayInterface;
import common.SearchResult;
import common.StatisticsCallback;
import common.Config;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Scanner;

/**
 * Implementação do Cliente RMI - interface do utilizador do sistema Googol.
 * 
 * <p>Responsabilidades principais:</p>
 * <ul>
 *   <li>Apresentar menu interativo ao utilizador</li>
 *   <li>Realizar pesquisas através da Gateway</li>
 *   <li>Indexar novos URLs</li>
 *   <li>Consultar backlinks de URLs</li>
 *   <li>Ver estatísticas do sistema em tempo real (callback push)</li>
 * </ul>
 * 
 * <p><b>Sistema de Estatísticas:</b> Regista callback na Gateway para receber
 * notificações push quando as estatísticas são atualizadas.</p>
 */
public class Client {

    /**
     * Método principal para iniciar o Cliente RMI.
     * Conecta à Gateway e apresenta o menu interativo.
     * 
     * @param args Argumentos de linha de comando (não utilizados)
     */
    public static void main(String[] args) {
        System.setProperty("java.security.policy", "security.policy");
        System.setProperty("java.rmi.server.hostname", "localhost");

        try {
            // Lê configurações do ficheiro config.properties
            String gatewayName = Config.getGatewayName();
            String rmiHost = Config.getRmiHost();
            int rmiPort = Config.getRmiPort();
            
            System.out.println("[Client] A ligar ao Gateway: " + gatewayName);
            System.out.println("[Client] RMI Registry: " + rmiHost + ":" + rmiPort);
            
            Registry registry = LocateRegistry.getRegistry(rmiHost, rmiPort);
            GatewayInterface gateway = (GatewayInterface) registry.lookup(gatewayName);

            System.out.println("Ligado à Googol Gateway. Bem-vindo!");
            
            runMenu(gateway);

        } catch (Exception e) {
            System.err.println("Exceção no Cliente: " + e.toString());
            e.printStackTrace();
        }
    }

    /**
     * Executa o loop principal do menu interativo.
     * 
     * <p>Opções disponíveis:</p>
     * <ol>
     *   <li>Pesquisar - Busca com múltiplos termos</li>
     *   <li>Indexar novo URL - Submete URL para crawling</li>
     *   <li>Ver backlinks - Lista URLs que apontam para um URL específico</li>
     *   <li>Ver Estatísticas - Estatísticas em tempo real com callback push</li>
     *   <li>Sair - Desliga o cliente</li>
     * </ol>
     * 
     * <p><b>Tolerância a falhas:</b> Se a Gateway ficar inacessível (cai ou desliga),
     * o cliente detecta automaticamente e encerra-se de forma limpa.</p>
     * 
     * @param gateway Referência RMI para a Gateway
     */
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
                    case "1":
                        System.out.print("Termos de pesquisa: ");
                        String query = scanner.nextLine();
                        List<SearchResult> results = gateway.search(query);
                        displayResultsWithPagination(results, scanner);
                        break;
                        
                    case "2":
                        System.out.print("URL para indexar: ");
                        String urlToIndex = scanner.nextLine();
                        gateway.indexNewURL(urlToIndex);
                        System.out.println("URL submetido para indexação.");
                        break;
                        
                    case "3":
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
                        
                    case "4":
                        displayRealTimeStatistics(gateway, scanner);
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
                System.err.println("\n✗ ERRO: Gateway não está acessível!");
                System.err.println("Detalhes: " + e.getMessage());
                System.err.println("\nA Gateway caiu ou está offline. O cliente vai encerrar.");
                running = false;
                break;
            } catch (Exception e) {
                System.err.println("\nOcorreu um erro inesperado: " + e.getMessage());
            }
        }
        scanner.close();
    }

    /**
     * Apresenta resultados de pesquisa com paginação (10 resultados por página).
     * Permite navegação interativa entre páginas.
     * 
     * @param results Lista de resultados de pesquisa
     * @param scanner Scanner para input do utilizador
     */
    private static void displayResultsWithPagination(List<SearchResult> results, Scanner scanner) {
        System.out.println("-- Resultados (" + results.size() + ") --");
        if (results.isEmpty()) {
            System.out.println("Nenhum resultado encontrado.");
            return;
        }

        for (int i = 0; i < results.size(); i++) {
            System.out.println(results.get(i));

            boolean moreResultsExist = (i + 1) < results.size();
            boolean isPageBoundary = (i + 1) % 10 == 0;

            if (isPageBoundary && moreResultsExist) {
                System.out.print("... (Mostrados " + (i + 1) + " de " + results.size() + ") Pressione ENTER para mais 10, ou 's' para parar... ");
                String next = scanner.nextLine();
                if (next.equalsIgnoreCase("s")) {
                    break;
                }
            }
        }
        System.out.println("-- Fim dos resultados --");
    }

    /**
     * Apresenta estatísticas do sistema em tempo real usando callbacks push.
     * Regista callback na Gateway para receber notificações automáticas.
     * 
     * <p><b>Tolerância a falhas:</b> Se a Gateway ficar inacessível durante
     * a visualização de estatísticas, o cliente encerra-se automaticamente.</p>
     * 
     * @param gateway Referência RMI para a Gateway
     * @param scanner Scanner para aguardar input do utilizador
     */
    private static void displayRealTimeStatistics(GatewayInterface gateway, Scanner scanner) {
        System.out.println("\n========================================");
        System.out.println("  ESTATÍSTICAS EM TEMPO REAL (PUSH)");
        System.out.println("========================================");
        System.out.println("As estatísticas são atualizadas automaticamente.");
        System.out.println("Prima ENTER para voltar ao menu.\n");

        StatisticsCallbackImpl callback = null;
        
        try {
            callback = new StatisticsCallbackImpl();
            
            gateway.registerStatisticsCallback(callback);
            System.out.println("Registado para receber atualizações...\n");
            
            scanner.nextLine();
            
            gateway.unregisterStatisticsCallback(callback);
            System.out.println("\nDesregistado. Voltando ao menu principal...\n");
            
        } catch (RemoteException e) {
            System.err.println("\n✗ ERRO: Gateway não está acessível!");
            System.err.println("Detalhes: " + e.getMessage());
            System.err.println("\nA Gateway caiu. O cliente vai encerrar.");
            System.exit(1);
        } finally {
            if (callback != null) {
                try {
                    UnicastRemoteObject.unexportObject(callback, true);
                } catch (Exception e) {
                }
            }
        }
    }
    
    /**
     * Implementação do callback RMI para receber atualizações push de estatísticas.
     * Cada vez que a Gateway atualiza as estatísticas, este callback é invocado remotamente.
     */
    private static class StatisticsCallbackImpl extends UnicastRemoteObject implements StatisticsCallback {
        private static final long serialVersionUID = 1L;
        private int updateCount = 0;
        
        /**
         * Cria um novo callback e exporta-o como objeto RMI.
         * 
         * @throws RemoteException Se houver falha ao exportar o objeto
         */
        protected StatisticsCallbackImpl() throws RemoteException {
            super();
        }
        
        /**
         * {@inheritDoc}
         * 
         * <p>Chamado automaticamente pela Gateway quando as estatísticas mudam.
         * Limpa o ecrã e imprime as estatísticas atualizadas.</p>
         */
        @Override
        public void onStatisticsUpdate(String statistics) throws RemoteException {
            updateCount++;
            
            clearScreen();
            
            // Cabeçalho
            System.out.println("========================================");
            System.out.println("  ESTATÍSTICAS DO GOOGOL (PUSH)");
            System.out.println("========================================");
            System.out.println("Atualizado: " + new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date()));
            System.out.println("Atualizações recebidas: " + updateCount);
            System.out.println("(Prima ENTER para voltar ao menu)\n");
            
            // Apresenta as estatísticas
            System.out.println(statistics);
            
            System.out.println("========================================");
            System.out.println("Aguardando próxima atualização...");
        }
    }

    /**
     * Limpa o ecrã do terminal usando códigos de escape ANSI.
     * Funciona na maioria dos terminais Unix/Linux/macOS.
     */
    private static void clearScreen() {
        try {
            final String os = System.getProperty("os.name");
            
            if (os.contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception e) {
            for (int i = 0; i < 50; i++) {
                System.out.println();
            }
        }
    }
}